package com.example.demo.service;

import com.example.demo.dto.BlindLocationRequest;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.UserRole;
import com.example.demo.repository.RunOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 盲人位置服务 —— 类似 VolunteerLocationService，双写 Redis + MySQL
 * 当前阶段仅写 Redis（供邻近感知使用）
 */
@Slf4j
@Service
public class BlindLocationService {

    private final StringRedisTemplate redisTemplate;
    private final RunOrderRepository runOrderRepository;
    private final NotificationService notificationService;
    private final RunOrderTrackService trackService;
    private final EscortSafetyService escortSafetyService;
    private final ObjectMapper objectMapper;

    /** Redis key 前缀 */
    private static final String REDIS_KEY_PREFIX = "blind:loc:";

    /** 志愿者位置 Redis key 前缀（与 VolunteerLocationService 的 REDIS_KEY_PREFIX 一致，直接读取避免循环依赖） */
    private static final String VOLUNTEER_REDIS_KEY_PREFIX = "vol:loc:";

    /** 位置 TTL（秒） */
    private static final long LOCATION_TTL_SECONDS = 30;

    public BlindLocationService(StringRedisTemplate redisTemplate,
                                 RunOrderRepository runOrderRepository,
                                 NotificationService notificationService,
                                 RunOrderTrackService trackService,
                                 EscortSafetyService escortSafetyService,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.runOrderRepository = runOrderRepository;
        this.notificationService = notificationService;
        this.trackService = trackService;
        this.escortSafetyService = escortSafetyService;
        this.objectMapper = objectMapper;
    }

    /**
     * 更新盲人位置
     */
    public void updateLocation(Long userId, BlindLocationRequest request) {
        double lat = request.getLatitude();
        double lng = request.getLongitude();
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("坐标范围不合法：纬度 -90~90，经度 -180~180");
        }

        String key = REDIS_KEY_PREFIX + userId;
        String value = request.getLatitude() + "," + request.getLongitude();
        redisTemplate.opsForValue().set(key, value, LOCATION_TTL_SECONDS, TimeUnit.SECONDS);

        log.debug("盲人位置已更新: userId={}, lat={}, lng={}", userId, request.getLatitude(), request.getLongitude());

        forwardLocationToVolunteer(userId, lat, lng);
    }

    /**
     * 将盲人位置转发给对应订单的志愿者，并在陪跑进行中记录轨迹 + 走散检测
     * 推送范围：DRIVER_EN_ROUTE、DRIVER_ARRIVED、IN_PROGRESS（与 VolunteerLocationService 对称）
     * 注：走散检测在双方上报侧均会触发；志愿者坐标直接读 Redis vol:loc:{id}（与
     * BlindLocationController 的 REST 降级读法一致），不注入 VolunteerLocationService，不产生循环依赖
     */
    private void forwardLocationToVolunteer(Long blindUserId, double lat, double lng) {
        try {
            List<RunOrder> activeOrders = runOrderRepository.findByBlindUserIdAndStatusInFetchVolunteer(
                    blindUserId,
                    List.of(OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED, OrderStatus.IN_PROGRESS)
            );

            for (RunOrder order : activeOrders) {
                if (order.getVolunteer() == null) {
                    continue;
                }
                Long volunteerId = order.getVolunteer().getId();
                notificationService.sendBlindLocationUpdate(volunteerId, order.getId(), lat, lng);
                log.debug("已向志愿者 {} 转发盲人 {} 位置 (订单 {})", volunteerId, blindUserId, order.getId());

                if (order.getStatus() == OrderStatus.IN_PROGRESS) {
                    trackService.recordIfDue(order.getId(), blindUserId, UserRole.BLIND, lat, lng);
                    checkEscortDistance(order, volunteerId, lat, lng);
                }
            }
        } catch (Exception e) {
            log.warn("转发盲人 {} 位置给志愿者失败: {}", blindUserId, e.getMessage());
        }
    }

    /**
     * 走散检测：读取志愿者最新缓存坐标，与盲人当前坐标比较
     */
    private void checkEscortDistance(RunOrder order, Long volunteerId, double blindLat, double blindLng) {
        try {
            Map<String, Double> volunteerLoc = getVolunteerLocation(volunteerId);
            if (volunteerLoc == null) {
                escortSafetyService.checkSignalMissing(order);
                return;
            }
            escortSafetyService.checkDistance(order, volunteerLoc.get("lat"), volunteerLoc.get("lng"), blindLat, blindLng);
        } catch (Exception e) {
            log.debug("走散检测失败 (订单 {}): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * 从 Redis 读取志愿者最新坐标（vol:loc:{volunteerId}，由 VolunteerLocationService 写入）
     *
     * @return lat/lng，key 不存在（离线/TTL 过期）或解析失败时返回 null
     */
    private Map<String, Double> getVolunteerLocation(Long volunteerId) {
        String json = redisTemplate.opsForValue().get(VOLUNTEER_REDIS_KEY_PREFIX + volunteerId);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, Double> location = new HashMap<>();
            location.put("lat", node.get("lat").asDouble());
            location.put("lng", node.get("lng").asDouble());
            return location;
        } catch (Exception e) {
            log.warn("解析志愿者 {} 位置数据失败: {}", volunteerId, e.getMessage());
            return null;
        }
    }

    /**
     * 清除盲人位置（WebSocket 断开时调用，与 VolunteerLocationService.setOffline() 对称）
     */
    public void clearLocation(Long userId) {
        redisTemplate.delete(REDIS_KEY_PREFIX + userId);
    }

    /**
     * 获取盲人位置
     *
     * @return Map with "lat", "lng" keys, or null if not found
     */
    public Map<String, Double> getLocation(Long userId) {
        String key = REDIS_KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.split(",");
            Map<String, Double> location = new HashMap<>();
            location.put("lat", Double.parseDouble(parts[0]));
            location.put("lng", Double.parseDouble(parts[1]));
            return location;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            log.warn("盲人位置数据格式异常: userId={}, value={}", userId, value);
            redisTemplate.delete(key);
            return null;
        }
    }
}

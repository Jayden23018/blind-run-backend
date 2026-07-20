package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.entity.VolunteerLocation;
import com.example.demo.entity.VolunteerProfile;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerLocationRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.util.GeoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 志愿者位置服务 —— 处理位置上报和查询
 *
 * 【核心职责】
 * 1. 保存志愿者上报的 GPS 位置（同时写入数据库和 Redis）
 * 2. 查询在线志愿者列表（优先从 Redis 读，Redis 无数据时降级查数据库）
 *
 * 【为什么要同时写数据库和 Redis？】
 * - Redis：读写速度快，适合高频实时查询（匹配算法用）
 * - 数据库：持久化存储，Redis 重启后数据不丢失
 *
 * 【Redis key 设计】
 * key:   vol:loc:{userId}
 * value: JSON {"lat": 39.9, "lng": 116.4, "isOnline": true}
 * TTL:   30 秒（超过30秒未更新，key 自动过期，视为离线）
 */
@Slf4j
@Service
public class VolunteerLocationService {

    private final VolunteerLocationRepository locationRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RunOrderRepository runOrderRepository;
    private final NotificationService notificationService;
    private final ProximityService proximityService;
    private final BlindLocationService blindLocationService;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final RunOrderTrackService trackService;
    private final EscortSafetyService escortSafetyService;

    /** Redis key 前缀 */
    private static final String REDIS_KEY_PREFIX = "vol:loc:";

    /** GEO 集合 key：存储所有在线志愿者的地理坐标 */
    private static final String GEO_KEY = "volunteers:geo";

    /** 志愿者位置 Redis TTL（秒） */
    @Value("${app.volunteer.location-ttl-seconds:30}")
    private long locationTtlSeconds;

    public VolunteerLocationService(VolunteerLocationRepository locationRepository,
                                     UserRepository userRepository,
                                     StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     RunOrderRepository runOrderRepository,
                                     NotificationService notificationService,
                                     ProximityService proximityService,
                                     BlindLocationService blindLocationService,
                                     VolunteerProfileRepository volunteerProfileRepository,
                                     RunOrderTrackService trackService,
                                     EscortSafetyService escortSafetyService) {
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.runOrderRepository = runOrderRepository;
        this.notificationService = notificationService;
        this.proximityService = proximityService;
        this.blindLocationService = blindLocationService;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.trackService = trackService;
        this.escortSafetyService = escortSafetyService;
    }

    /**
     * 应用启动完成后清空 GEO 集合
     *
     * 问题背景（C3）：服务器重启后 vol:loc:* 键全部消失（TTL 到期），
     * 但 GEO 集合仍保留上次运行期间的所有成员。
     * 若不清空，GEORADIUS 会返回已全部离线的旧成员，导致派单向无效志愿者推送。
     *
     * 解决方案：启动时清空 GEO 集合，让它随志愿者重新上线后自然重建。
     * 使用 ApplicationReadyEvent（而非 @PostConstruct）确保 Redis 连接已就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void clearGeoSetOnStartup() {
        try {
            Boolean deleted = redisTemplate.delete(GEO_KEY);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("应用启动：GEO 集合已清空，待志愿者重新上线后自动重建");
            } else {
                log.info("应用启动：GEO 集合不存在，无需清空");
            }
        } catch (Exception e) {
            log.warn("应用启动时清空 GEO 集合失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 更新志愿者位置
     *
     * 【流程】
     * 1. 查数据库是否已有该志愿者的位置记录
     * 2. 有 → 更新，没有 → 新建
     * 3. 同时写入 Redis（设 TTL 30秒）
     *
     * @param userId   志愿者用户ID
     * @param latitude 纬度
     * @param longitude 经度
     * @param isOnline  是否在线
     */
    public void updateLocation(Long userId, Double latitude, Double longitude, Boolean isOnline) {
        GeoUtils.validateCoordinates(latitude, longitude);

        // 1. 更新数据库（UPSERT 逻辑）
        VolunteerLocation location = locationRepository.findByVolunteerId(userId)
                .orElseGet(() -> {
                    // 不存在则新建
                    VolunteerLocation newLoc = new VolunteerLocation();
                    User volunteer = userRepository.getReferenceById(userId);
                    newLoc.setVolunteer(volunteer);
                    return newLoc;
                });

        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setIsOnline(isOnline != null ? isOnline : true);
        location.setUpdatedAt(LocalDateTime.now());
        locationRepository.save(location);

        // 2. 写入 Redis（保留已有的 wantsDispatch 标志）
        try {
            String key = REDIS_KEY_PREFIX + userId;
            boolean wantsDispatch = readWantsDispatch(key);

            Map<String, Object> value = new HashMap<>();
            value.put("userId", userId);
            value.put("lat", latitude);
            value.put("lng", longitude);
            value.put("isOnline", isOnline != null ? isOnline : true);
            value.put("wantsDispatch", wantsDispatch);
            value.put("updatedAt", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, locationTtlSeconds, TimeUnit.SECONDS);

            // 同步更新 GEO 集合（Point 参数顺序：longitude 在前，latitude 在后）
            redisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), String.valueOf(userId));

            log.debug("志愿者 {} 位置已更新: lat={}, lng={}", userId, latitude, longitude);
        } catch (Exception e) {
            // Redis 写入失败不影响主流程，记录警告日志
            log.warn("Redis 写入志愿者 {} 位置失败: {}", userId, e.getMessage());
        }

        // 3. 实时位置转发：如果志愿者有进行中的订单，推送位置给对应的盲人用户
        forwardLocationToBlind(userId, latitude, longitude);
    }

    /**
     * 将志愿者设为离线 —— 订单完成时调用，清除位置数据
     */
    public void setOffline(Long userId) {
        // 1. 数据库设 isOnline=false
        locationRepository.findByVolunteerId(userId).ifPresent(loc -> {
            loc.setIsOnline(false);
            loc.setUpdatedAt(LocalDateTime.now());
            locationRepository.save(loc);
        });

        // 2. 删除 Redis 位置 key 并移出 GEO 集合
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX + userId);
            redisTemplate.opsForGeo().remove(GEO_KEY, String.valueOf(userId));
            log.debug("志愿者 {} 已设为离线", userId);
        } catch (Exception e) {
            log.warn("清除志愿者 {} Redis 位置失败: {}", userId, e.getMessage());
        }
    }

    /**
     * 更新志愿者接单开关，不改变位置和在线状态。
     *
     * 【持久化优先】先落库到 volunteer_profile.wants_dispatch（数据源），再同步 Redis 热路径。
     * 即使 Redis 位置 key 不存在（TTL 过期/从未上报位置），也会落库 + 创建最小 Redis 记录，
     * 避免之前"key 不存在时静默 return 导致开关丢失"的 bug。
     *
     * 用于 PUT /api/volunteer/dispatch-status 入口。
     */
    public void updateDispatchStatus(Long userId, boolean wantsDispatch) {
        // 1. 落库（数据源）
        volunteerProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setWantsDispatch(wantsDispatch);
            volunteerProfileRepository.save(profile);
        });
        // 2. 同步 Redis 热路径
        syncWantsDispatchToRedis(userId, wantsDispatch);
    }

    /**
     * 仅同步接单开关到 Redis（不落库）。供 VolunteerService.updateProfile 在已落库后调用，
     * 避免重复 save。Redis 失败不影响 DB 结果，派单时会回退读 DB。
     */
    public void syncWantsDispatchToRedis(Long userId, boolean wantsDispatch) {
        String key = REDIS_KEY_PREFIX + userId;
        try {
            String existing = redisTemplate.opsForValue().get(key);
            long remainTtl = locationTtlSeconds;
            Map<String, Object> data;
            if (existing != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new HashMap<>(objectMapper.readValue(existing, Map.class));
                data = parsed;
                Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                if (ttl != null && ttl > 0) remainTtl = ttl;
            } else {
                // key 不存在：创建仅含开关的最小记录（无坐标），下次位置上报会补全
                data = new HashMap<>();
                data.put("isOnline", false);
            }
            data.put("wantsDispatch", wantsDispatch);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(data), remainTtl, TimeUnit.SECONDS);
            log.info("志愿者 {} 接单开关已同步到 Redis: {}", userId, wantsDispatch);
        } catch (Exception e) {
            log.warn("同步志愿者 {} 接单开关到 Redis 失败: {}", userId, e.getMessage());
        }
    }

    /** 从 Redis 读取现有的 wantsDispatch 值，key 不存在时默认 true */
    private boolean readWantsDispatch(String key) {
        try {
            String existing = redisTemplate.opsForValue().get(key);
            if (existing == null) return true;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(existing, Map.class);
            Object flag = data.get("wantsDispatch");
            return flag == null || Boolean.TRUE.equals(flag);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 将志愿者位置转发给对应订单的盲人用户
     * 推送范围：DRIVER_EN_ROUTE（前往中）、DRIVER_ARRIVED（已到达）、IN_PROGRESS（陪跑进行中）
     */
    private void forwardLocationToBlind(Long volunteerId, Double latitude, Double longitude) {
        try {
            List<RunOrder> activeOrders = runOrderRepository.findByVolunteerIdAndStatusInFetchBlind(
                    volunteerId,
                    List.of(OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED, OrderStatus.IN_PROGRESS)
            );

            if (activeOrders.isEmpty()) {
                return;
            }

            for (RunOrder order : activeOrders) {
                Long blindUserId = order.getBlindUser().getId();
                notificationService.sendVolunteerLocationUpdate(blindUserId, order.getId(), latitude, longitude);
                log.debug("已向盲人 {} 转发志愿者 {} 位置 (订单 {})", blindUserId, volunteerId, order.getId());

                if (order.getStatus() == OrderStatus.DRIVER_EN_ROUTE) {
                    // 邻近检测：志愿者出发途中，检查是否已接近盲人
                    checkProximity(order, blindUserId, volunteerId, latitude, longitude);
                } else if (order.getStatus() == OrderStatus.IN_PROGRESS) {
                    // 陪跑进行中：记录轨迹 + 走散检测
                    trackService.recordIfDue(order.getId(), volunteerId, UserRole.VOLUNTEER, latitude, longitude);
                    checkEscortDistance(order, blindUserId, latitude, longitude);
                }
            }
        } catch (Exception e) {
            // 位置转发失败不影响主流程
            log.warn("转发志愿者 {} 位置给盲人失败: {}", volunteerId, e.getMessage());
        }
    }

    /**
     * 走散检测：读取盲人最新缓存坐标，与志愿者当前坐标比较
     */
    private void checkEscortDistance(RunOrder order, Long blindUserId, double volunteerLat, double volunteerLng) {
        try {
            Map<String, Double> blindLoc = blindLocationService.getLocation(blindUserId);
            if (blindLoc == null) {
                escortSafetyService.checkSignalMissing(order);
                return;
            }
            escortSafetyService.checkDistance(order, volunteerLat, volunteerLng, blindLoc.get("lat"), blindLoc.get("lng"));
        } catch (Exception e) {
            log.debug("走散检测失败 (订单 {}): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * 邻近检测：如果盲人有上报位置，检查志愿者是否已接近
     */
    private void checkProximity(RunOrder order, Long blindUserId, Long volunteerId,
                                 double volunteerLat, double volunteerLng) {
        try {
            Map<String, Double> blindLoc = blindLocationService.getLocation(blindUserId);
            if (blindLoc == null) {
                return;
            }
            proximityService.checkAndNotify(
                    order.getId(), blindUserId, volunteerId,
                    volunteerLat, volunteerLng,
                    blindLoc.get("lat"), blindLoc.get("lng")
            );
        } catch (Exception e) {
            log.debug("邻近检测失败 (订单 {}): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * 获取单个志愿者的位置信息（从 Redis 读取）
     *
     * @return 位置信息 Map（userId, lat, lng, isOnline），不存在则返回 null
     */
    public Map<String, Object> getVolunteerLocation(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + userId);
            if (json == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            if (Boolean.TRUE.equals(data.get("isOnline"))) {
                return data;
            }
            return null;
        } catch (Exception e) {
            log.warn("读取志愿者 {} 位置失败: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 获取所有在线志愿者的位置信息
     *
     * 【策略】
     * 1. 先查 Redis（速度快）
     * 2. Redis 无数据时，降级查数据库
     *
     * @return 在线志愿者位置列表，每项包含 userId、latitude、longitude
     */
    public List<Map<String, Object>> getOnlineVolunteerLocations() {
        // 1. 用 SCAN 遍历 Redis（避免 keys() 阻塞）
        List<Map<String, Object>> result = new ArrayList<>();
        try (var cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(REDIS_KEY_PREFIX + "*").count(100).build())) {
            cursor.forEachRemaining(key -> {
                try {
                    String json = redisTemplate.opsForValue().get(key);
                    if (json != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = objectMapper.readValue(json, Map.class);
                        if (Boolean.TRUE.equals(data.get("isOnline"))) {
                            result.add(data);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析 Redis 志愿者位置数据失败: {}", e.getMessage());
                }
            });
        }
        if (!result.isEmpty()) {
            log.debug("从 Redis 获取到 {} 名在线志愿者", result.size());
            return result;
        }

        // 2. Redis 无数据，降级查数据库
        log.info("Redis 无在线志愿者数据，降级查询数据库");
        List<VolunteerLocation> dbLocations = locationRepository.findByIsOnlineTrue();
        result.clear();
        for (VolunteerLocation loc : dbLocations) {
            Map<String, Object> data = new HashMap<>();
            data.put("userId", loc.getVolunteer().getId());
            data.put("lat", loc.getLatitude());
            data.put("lng", loc.getLongitude());
            data.put("isOnline", true);
            result.add(data);
        }

        log.debug("从数据库获取到 {} 名在线志愿者", result.size());
        return result;
    }

    /**
     * 获取指定坐标 radiusKm 范围内的在线志愿者（基于 Redis GEO）
     *
     * 流程：
     * 1. GEOSEARCH 返回 GEO 集合中半径内的成员 ID 列表
     * 2. MGET 批量检查哪些仍有 vol:loc:{id} key（TTL 未过期 = 仍在线）
     * 3. 懒清理：移除已过期成员（避免 GEO 集合无限膨胀）
     * 4. GEO 集合不存在时降级到全量 SCAN（首次启动或 Redis 重启后的过渡期）
     *
     * @param lat      订单起点纬度
     * @param lng      订单起点经度
     * @param radiusKm 搜索半径（公里）
     * @return 在线志愿者位置信息列表（与 getOnlineVolunteerLocations 格式一致）
     */
    public List<Map<String, Object>> getVolunteersNear(double lat, double lng, double radiusKm) {
        try {
            // GEO 集合不存在（Redis 重启或首次部署），降级全量 SCAN
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(GEO_KEY))) {
                log.debug("GEO集合不存在，降级到全量SCAN");
                return getOnlineVolunteerLocations();
            }

            // GEORADIUS：Point 参数顺序 longitude, latitude
            Circle within = new Circle(new Point(lng, lat), new Distance(radiusKm, Metrics.KILOMETERS));
            GeoResults<GeoLocation<String>> geoResults = redisTemplate.opsForGeo().radius(GEO_KEY, within);
            if (geoResults == null || geoResults.getContent().isEmpty()) {
                return List.of();
            }

            List<String> memberIds = geoResults.getContent().stream()
                    .map(r -> r.getContent().getName())
                    .collect(Collectors.toList());

            // MGET 批量检查在线状态（一次网络往返）
            List<String> locKeys = memberIds.stream()
                    .map(id -> REDIS_KEY_PREFIX + id)
                    .collect(Collectors.toList());
            List<String> locJsons = redisTemplate.opsForValue().multiGet(locKeys);

            List<Map<String, Object>> result = new ArrayList<>();
            List<String> stale = new ArrayList<>();

            for (int i = 0; i < memberIds.size(); i++) {
                String json = locJsons != null ? locJsons.get(i) : null;
                if (json == null) {
                    stale.add(memberIds.get(i));  // vol:loc 已过期，加入懒清理列表
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = objectMapper.readValue(json, Map.class);
                    if (Boolean.TRUE.equals(data.get("isOnline"))) {
                        result.add(data);
                    }
                } catch (Exception e) {
                    log.warn("解析志愿者位置数据失败: {}", e.getMessage());
                }
            }

            // 懒清理：将 vol:loc 已过期的成员从 GEO 集合中移除
            if (!stale.isEmpty()) {
                try {
                    redisTemplate.opsForGeo().remove(GEO_KEY, stale.toArray(new String[0]));
                    log.debug("GEO懒清理：移除 {} 个已离线成员", stale.size());
                } catch (Exception e) {
                    log.warn("GEO懒清理失败: {}", e.getMessage());
                }
            }

            log.debug("GEO查询 {}km 内在线志愿者: {} 人（过期清理 {} 人）",
                    radiusKm, result.size(), stale.size());
            return result;

        } catch (Exception e) {
            log.warn("GEO查询失败，降级到全量SCAN: {}", e.getMessage());
            return getOnlineVolunteerLocations();
        }
    }
}

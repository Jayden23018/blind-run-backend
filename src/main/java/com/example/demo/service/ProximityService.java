package com.example.demo.service;

import com.example.demo.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 邻近感知服务 —— 当志愿者距离盲人 ≤ 阈值（默认 100 米）时推送通知
 */
@Slf4j
@Service
public class ProximityService {

    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.proximity.threshold-meters:100}")
    private double thresholdMeters;

    public ProximityService(NotificationService notificationService, StringRedisTemplate redisTemplate) {
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查并推送邻近感知通知
     *
     * @param orderId       订单 ID
     * @param blindUserId   盲人用户 ID
     * @param volunteerId   志愿者用户 ID
     * @param volunteerLat  志愿者当前纬度
     * @param volunteerLng  志愿者当前经度
     * @param blindLat      盲人当前纬度
     * @param blindLng      盲人当前经度
     */
    public void checkAndNotify(Long orderId, Long blindUserId, Long volunteerId,
                                double volunteerLat, double volunteerLng,
                                double blindLat, double blindLng) {
        // 检查是否已通知过
        String notifiedKey = "proximity:notified:" + orderId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(notifiedKey))) {
            return;
        }

        // 计算距离（GeoUtils.distanceKm 返回公里，转米）
        double distanceKm = GeoUtils.distanceKm(volunteerLat, volunteerLng, blindLat, blindLng);
        double distanceMeters = distanceKm * 1000;

        if (distanceMeters <= thresholdMeters) {
            // 推送通知
            notificationService.sendProximityAlert(orderId, blindUserId, volunteerId, distanceMeters);

            // 标记已通知（防止重复推送）— TTL 24h 防止 key 永久残留
            redisTemplate.opsForValue().set(notifiedKey, "1", 24, TimeUnit.HOURS);
            log.info("邻近感知触发! orderId={}, 距离={}米", orderId, Math.round(distanceMeters));
        }
    }

    /**
     * 订单结束时清除邻近感知标记
     */
    public void clearProximityFlag(Long orderId) {
        redisTemplate.delete("proximity:notified:" + orderId);
    }
}

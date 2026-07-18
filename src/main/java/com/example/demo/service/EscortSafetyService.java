package com.example.demo.service;

import com.example.demo.dto.EmergencyTriggerRequest;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.TriggerType;
import com.example.demo.exception.RateLimitException;
import com.example.demo.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 走散检测 —— 陪跑进行中双方坐标距离超过阈值时自动触发紧急事件
 *
 * 复用 EmergencyService 现有的分级升级 + 冷却去重机制，不用另起一套通知逻辑。
 * 单次 GPS 跳变（城市峡谷/隧道口）不应直接拉起应急升级，故要求连续
 * {@link #CONSECUTIVE_BREACHES_REQUIRED} 次采样都超阈值才真正触发。
 */
@Slf4j
@Service
public class EscortSafetyService {

    private static final int CONSECUTIVE_BREACHES_REQUIRED = 2;
    private static final String BREACH_COUNT_KEY_PREFIX = "escort:breach:";
    private static final long BREACH_COUNT_TTL_SECONDS = 60;

    private final EmergencyService emergencyService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.escort.max-distance-meters:100}")
    private double maxDistanceMeters;

    public EscortSafetyService(EmergencyService emergencyService, NotificationService notificationService,
                                StringRedisTemplate redisTemplate) {
        this.emergencyService = emergencyService;
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
    }

    public void checkDistance(RunOrder order, double volunteerLat, double volunteerLng,
                               double blindLat, double blindLng) {
        double distanceMeters = GeoUtils.distanceKm(volunteerLat, volunteerLng, blindLat, blindLng) * 1000;
        String breachCountKey = BREACH_COUNT_KEY_PREFIX + order.getId();
        if (distanceMeters <= maxDistanceMeters) {
            redisTemplate.delete(breachCountKey);
            return;
        }

        Long breachCount = redisTemplate.opsForValue().increment(breachCountKey);
        redisTemplate.expire(breachCountKey, Duration.ofSeconds(BREACH_COUNT_TTL_SECONDS));
        if (breachCount == null || breachCount < CONSECUTIVE_BREACHES_REQUIRED) {
            log.debug("走散检测：距离超阈值但未达连续确认次数 ({}/{}), orderId={}",
                    breachCount, CONSECUTIVE_BREACHES_REQUIRED, order.getId());
            return;
        }
        redisTemplate.delete(breachCountKey);

        notificationService.sendEscortDistanceAlert(order.getId(), order.getBlindUser().getId(), order.getVolunteer().getId());

        EmergencyTriggerRequest request = new EmergencyTriggerRequest();
        request.setOrderId(order.getId());
        request.setGpsLat(BigDecimal.valueOf(blindLat));
        request.setGpsLng(BigDecimal.valueOf(blindLng));

        try {
            emergencyService.triggerEmergency(order.getBlindUser().getId(), request, TriggerType.AI_DETECTED);
            log.warn("走散检测触发! orderId={}, 距离={}米", order.getId(), Math.round(distanceMeters));
        } catch (RateLimitException e) {
            // 冷却期内，EmergencyService 已经处理过一次，无需重复触发
        }
    }
}

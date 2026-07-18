package com.example.demo.service;

import com.example.demo.entity.RunOrder;
import com.example.demo.entity.TriggerType;
import com.example.demo.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EscortSafetyService 单元测试 —— 走散检测阈值触发/不触发 + 连续确认防抖
 */
@ExtendWith(MockitoExtension.class)
class EscortSafetyServiceTest {

    @Mock private EmergencyService emergencyService;
    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private EscortSafetyService escortSafetyService;

    @BeforeEach
    void setUp() {
        escortSafetyService = new EscortSafetyService(emergencyService, notificationService, redisTemplate);
        ReflectionTestUtils.setField(escortSafetyService, "maxDistanceMeters", 100.0);
    }

    private RunOrder order() {
        RunOrder order = new RunOrder();
        order.setId(1L);
        User blind = new User();
        blind.setId(100L);
        order.setBlindUser(blind);
        User volunteer = new User();
        volunteer.setId(200L);
        order.setVolunteer(volunteer);
        return order;
    }

    @Test
    void checkDistance_withinThreshold_doesNotTrigger() {
        // 同一坐标，距离 0
        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.23, 121.47);

        verify(emergencyService, never()).triggerEmergency(any(), any(), any());
        verify(notificationService, never()).sendEscortDistanceAlert(any(), any(), any());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void checkDistance_singleBreach_doesNotTriggerYet() {
        // 第一次超阈值：只是 GPS 抖动的可能性，不应立即触发
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("escort:breach:1")).thenReturn(1L);

        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.24, 121.47);

        verify(emergencyService, never()).triggerEmergency(any(), any(), any());
        verify(notificationService, never()).sendEscortDistanceAlert(any(), any(), any());
    }

    @Test
    void checkDistance_twoConsecutiveBreaches_triggersEmergencyAndAlert() {
        // 纬度相差约 0.01 度 ≈ 1.1 公里，远超 100 米阈值；连续两次采样都超阈值才触发
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("escort:breach:1")).thenReturn(1L, 2L);

        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.24, 121.47);
        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.24, 121.47);

        verify(emergencyService).triggerEmergency(eq(100L), any(), eq(TriggerType.AI_DETECTED));
        verify(notificationService).sendEscortDistanceAlert(1L, 100L, 200L);
    }

    @Test
    void checkDistance_returnsWithinThreshold_resetsBreachCounter() {
        // 一次超阈值后恢复正常，计数器应清零，防止后续单次偶发超阈值就误触发
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("escort:breach:1")).thenReturn(1L);

        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.24, 121.47);
        escortSafetyService.checkDistance(order(), 31.23, 121.47, 31.23, 121.47);

        verify(redisTemplate, times(1)).delete("escort:breach:1");
        verify(emergencyService, never()).triggerEmergency(any(), any(), any());
    }
}

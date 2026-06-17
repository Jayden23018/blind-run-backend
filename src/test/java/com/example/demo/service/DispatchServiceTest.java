package com.example.demo.service;

import com.example.demo.exception.OrderStatusException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.VolunteerAvailableTimeRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * DispatchService 单元测试 —— 聚焦 B2：派单归属校验。
 *
 * <p>核心不变量：只有"当前被派单的志愿者"（Redis CURRENT_KEY 指向的 ID）才能接单/拒单。
 * 该不变量此前零覆盖，是旧 /accept（走 OrderLifecycleService.acceptOrder）能绕过串行派单
 * 协议、任意识别志愿者抢单的根因。B2 修复后旧 /accept 与 /respond 同走本校验。
 *
 * <p>纯 Mockito 单测：直接打桩 Redis CURRENT_KEY 的返回值，不依赖派单评分/时序，稳定可靠。
 */
@ExtendWith(MockitoExtension.class)
class DispatchServiceTest {

    @Mock private ScoringService scoringService;
    @Mock private VolunteerLocationService volunteerLocationService;
    @Mock private VolunteerProfileRepository volunteerProfileRepository;
    @Mock private VolunteerAvailableTimeRepository availableTimeRepository;
    @Mock private RunOrderRepository runOrderRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(scoringService, volunteerLocationService,
                volunteerProfileRepository, availableTimeRepository, runOrderRepository,
                redisTemplate, notificationService, eventPublisher, objectMapper);
    }

    /** B2：订单派给 100，志愿者 200 接单应被拒（非当前派单对象） */
    @Test
    void handleAccept_wrongVolunteer_throws() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(contains("dispatch:current"))).thenReturn("100");

        assertThrows(OrderStatusException.class,
                () -> dispatchService.handleAccept(1L, 200L));
    }

    /** B2：无当前派单（CURRENT_KEY 不存在），任何志愿者接单应被拒 */
    @Test
    void handleAccept_noCurrentVolunteer_throws() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(contains("dispatch:current"))).thenReturn(null);

        assertThrows(OrderStatusException.class,
                () -> dispatchService.handleAccept(1L, 100L));
    }

    /** B2：handleDecline 同样校验派单归属（订单派给 100，志愿者 200 拒单应被拒） */
    @Test
    void handleDecline_wrongVolunteer_throws() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(contains("dispatch:current"))).thenReturn("100");

        assertThrows(OrderStatusException.class,
                () -> dispatchService.handleDecline(1L, 200L));
    }
}

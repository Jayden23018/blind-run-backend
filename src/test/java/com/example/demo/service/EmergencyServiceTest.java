package com.example.demo.service;

import com.example.demo.dto.EmergencyTriggerRequest;
import com.example.demo.entity.*;
import com.example.demo.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EmergencyService 单元测试 —— 聚焦 A1（独立 SOS，orderId 可空）与 A2（formatLocation 三级降级）。
 *
 * 四个测试均走"无订单触发 → 升级联系人"路径，借 sendEmergencyAlertSms 的 location 参数
 * 反向验证 formatLocation 的输出。
 */
@ExtendWith(MockitoExtension.class)
class EmergencyServiceTest {

    @Mock private EmergencyEventRepository eventRepository;
    @Mock private EmergencyNotificationRepository notificationRepository;
    @Mock private RunOrderRepository runOrderRepository;
    @Mock private EmergencyContactRepository contactRepository;
    @Mock private CSUserRepository csUserRepository;
    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private UserRepository userRepository;
    @Mock private GeocodingService geocodingService;

    private EmergencyService emergencyService;

    @BeforeEach
    void setUp() {
        emergencyService = new EmergencyService(eventRepository, notificationRepository,
                runOrderRepository, contactRepository, csUserRepository, notificationService,
                redisTemplate, userRepository, geocodingService);
        ReflectionTestUtils.setField(emergencyService, "cooldownSeconds", 60);
        ReflectionTestUtils.setField(emergencyService, "volunteerTimeoutSeconds", 30);
    }

    /** 冷却放行 + 模拟 JPA save 赋 id 与 triggeredAt + 主联系人 + 触发用户 */
    private void mockHappyPathDependencies() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(eventRepository.save(any(EmergencyEvent.class))).thenAnswer(inv -> {
            EmergencyEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            if (e.getTriggeredAt() == null) e.setTriggeredAt(LocalDateTime.now());
            return e;
        });

        EmergencyContact contact = new EmergencyContact();
        contact.setId(7L);
        contact.setName("家属");
        contact.setPhone("13800000000");
        when(contactRepository.findByUserIdAndIsPrimaryTrue(100L)).thenReturn(Optional.of(contact));

        User user = new User();
        user.setId(100L);
        user.setName("张三");
        when(userRepository.findById(100L)).thenReturn(Optional.of(user));
    }

    private String captureSmsLocation() {
        ArgumentCaptor<String> loc = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendEmergencyAlertSms(anyString(), anyString(), anyString(), loc.capture());
        return loc.getValue();
    }

    /** A1：无 orderId 的独立 SOS 能成功触发，升级联系人 + 推送客服，且不查订单 */
    @Test
    void triggerEmergency_withoutOrderId_escalatesAndAlertsCs() {
        mockHappyPathDependencies();
        when(geocodingService.reverseGeocode(any(), any())).thenReturn(Optional.of("上海市浦东新区世纪大道100号"));

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setOrderId(null); // 独立 SOS
        req.setGpsLat(new BigDecimal("31.23"));
        req.setGpsLng(new BigDecimal("121.47"));

        EmergencyEvent event = emergencyService.triggerEmergency(100L, req);

        assertNotNull(event);
        assertNull(event.getOrderId());
        assertEquals(EmergencyStatus.CONTACT_NOTIFIED, event.getStatus());
        verify(runOrderRepository, never()).findById(any()); // 无订单不查订单
        verify(notificationService).sendEmergencyAlert(event); // 推送客服
    }

    /** A2 分支1：无坐标 → 引导语，且不调用地理编码 */
    @Test
    void formatLocation_noCoords_returnsGuidance() {
        mockHappyPathDependencies();

        EmergencyTriggerRequest req = new EmergencyTriggerRequest(); // 无坐标
        emergencyService.triggerEmergency(100L, req);

        String location = captureSmsLocation();
        assertTrue(location.contains("位置获取失败"));
        verify(geocodingService, never()).reverseGeocode(any(), any());
    }

    /** A2 分支2：地理编码成功 → 文字地址 */
    @Test
    void formatLocation_geocodeSucceeds_returnsAddress() {
        mockHappyPathDependencies();
        when(geocodingService.reverseGeocode(any(), any())).thenReturn(Optional.of("上海市浦东新区世纪大道"));

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setGpsLat(new BigDecimal("31.23"));
        req.setGpsLng(new BigDecimal("121.47"));
        emergencyService.triggerEmergency(100L, req);

        assertEquals("上海市浦东新区世纪大道", captureSmsLocation());
    }

    /** A2 分支3：有坐标但地理编码失败 → 降级为可读经纬度（满足短信 35 字符限制） */
    @Test
    void formatLocation_geocodeFails_returnsReadableCoords() {
        mockHappyPathDependencies();
        when(geocodingService.reverseGeocode(any(), any())).thenReturn(Optional.empty());

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setGpsLat(new BigDecimal("31.234567"));
        req.setGpsLng(new BigDecimal("121.478901"));
        emergencyService.triggerEmergency(100L, req);

        String location = captureSmsLocation();
        assertTrue(location.contains("纬度"));
        assertTrue(location.contains("经度"));
        assertTrue(location.length() <= 35, "短信变量须 ≤35 字符，实际: " + location);
    }

    /** A2：文字地址超长（>30字符）截断到 30 字符，满足阿里云短信 ≤35 字符限制 */
    @Test
    void formatLocation_longAddress_truncatedTo30() {
        mockHappyPathDependencies();
        String longAddr = "上海市浦东新区".repeat(10); // 70 字符，触发 substring(0,30)
        when(geocodingService.reverseGeocode(any(), any())).thenReturn(Optional.of(longAddr));

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setGpsLat(new BigDecimal("31.23"));
        req.setGpsLng(new BigDecimal("121.47"));
        emergencyService.triggerEmergency(100L, req);

        String location = captureSmsLocation();
        assertEquals(30, location.length(), "超长地址应截断到 30 字符，满足阿里云短信 ≤35 限制");
    }

    /** A1 边界：冷却期内重复触发抛 IllegalStateException，且不查订单/不创建事件/不调用地理编码 */
    @Test
    void triggerEmergency_withinCooldown_throwsAndSkipsProcessing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false); // 冷却命中

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setGpsLat(new BigDecimal("31.23"));

        assertThrows(IllegalStateException.class, () -> emergencyService.triggerEmergency(100L, req));
        verify(runOrderRepository, never()).findById(any());
        verify(eventRepository, never()).save(any());
        verify(geocodingService, never()).reverseGeocode(any(), any());
    }

    /** S5：无紧急联系人时，盲人收到 EMERGENCY_NO_CONTACT 通知，事件转 CS_HANDLING（防 TimeoutScheduler 重复 escalate） */
    @Test
    void escalate_noPrimaryContact_notifiesBlindAndHandsToCs() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(eventRepository.save(any(EmergencyEvent.class))).thenAnswer(inv -> {
            EmergencyEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            if (e.getTriggeredAt() == null) e.setTriggeredAt(LocalDateTime.now());
            return e;
        });
        when(contactRepository.findByUserIdAndIsPrimaryTrue(100L)).thenReturn(Optional.empty());

        EmergencyTriggerRequest req = new EmergencyTriggerRequest();
        req.setGpsLat(new BigDecimal("31.23"));
        req.setGpsLng(new BigDecimal("121.47"));
        EmergencyEvent event = emergencyService.triggerEmergency(100L, req);

        verify(notificationService).sendNotification(eq(100L), eq("EMERGENCY_NO_CONTACT"),
                eq(TargetRole.BLIND_USER), isNull());
        assertEquals(EmergencyStatus.CS_HANDLING, event.getStatus());
        verify(notificationService, never()).sendEmergencyAlertSms(anyString(), anyString(), anyString(), anyString());
    }
}

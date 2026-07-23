package com.example.demo.service;

import com.example.demo.entity.NotificationTemplate;
import com.example.demo.entity.NotificationPriority;
import com.example.demo.entity.TargetRole;
import com.example.demo.repository.NotificationLogRepository;
import com.example.demo.repository.NotificationTemplateRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.websocket.UnifiedSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NotificationService 单元测试 —— 消息信封 messageId 去重字段
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private UnifiedSessionRegistry sessionRegistry;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private NotificationTemplateRepository templateRepository;
    @Mock private SmsService smsService;
    @Mock private UserRepository userRepository;

    private NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(sessionRegistry, notificationLogRepository,
                templateRepository, objectMapper, smsService, userRepository);
    }

    @Test
    void sendAppNotification_includesNonEmptyMessageId() throws Exception {
        notificationService.sendAppNotification(1L, "title", "body");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(eq(1L), payload.capture());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.has("messageId")).isTrue();
        assertThat(json.get("messageId").asText()).isNotBlank();
    }

    @Test
    void sendAppNotification_messageIdIsUniquePerCall() throws Exception {
        notificationService.sendAppNotification(1L, "title", "body");
        notificationService.sendAppNotification(1L, "title", "body");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry, times(2)).sendToUser(eq(1L), payload.capture());

        String first = objectMapper.readTree(payload.getAllValues().get(0)).get("messageId").asText();
        String second = objectMapper.readTree(payload.getAllValues().get(1)).get("messageId").asText();
        assertThat(first).isNotEqualTo(second);
    }

    // 走散告警/信号缺失告警实际下发的 WS 消息结构快照 —— 防止 frontend-guide.md 文档与实现再次漂移
    @Test
    void sendEscortDistanceAlert_payloadMatchesDocumentedEnvelope() throws Exception {
        when(templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue("ESCORT_DISTANCE_ALERT", TargetRole.BLIND_USER))
                .thenReturn(java.util.Optional.of(template("您与志愿者距离过远", "距离过远语音提示")));
        when(templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue("ESCORT_DISTANCE_ALERT", TargetRole.VOLUNTEER))
                .thenReturn(java.util.Optional.of(template("您与盲人用户距离过远", "距离过远语音提示")));

        notificationService.sendEscortDistanceAlert(1L, 100L, 200L);

        assertEnvelopeShape(capturePayload(100L), "ESCORT_DISTANCE_ALERT");
        assertEnvelopeShape(capturePayload(200L), "ESCORT_DISTANCE_ALERT");
    }

    @Test
    void sendEscortSignalLostAlert_payloadMatchesDocumentedEnvelope() throws Exception {
        when(templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue("ESCORT_SIGNAL_LOST", TargetRole.BLIND_USER))
                .thenReturn(java.util.Optional.of(template("与志愿者失去联系", "失去联系语音提示")));
        when(templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue("ESCORT_SIGNAL_LOST", TargetRole.VOLUNTEER))
                .thenReturn(java.util.Optional.of(template("与盲人用户失去联系", "失去联系语音提示")));

        notificationService.sendEscortSignalLostAlert(1L, 100L, 200L);

        assertEnvelopeShape(capturePayload(100L), "ESCORT_SIGNAL_LOST");
        assertEnvelopeShape(capturePayload(200L), "ESCORT_SIGNAL_LOST");
    }

    private NotificationTemplate template(String text, String tts) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateText(text);
        t.setTtsText(tts);
        t.setPriority(NotificationPriority.HIGH);
        return t;
    }

    private String capturePayload(Long userId) throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(eq(userId), payload.capture());
        return payload.getValue();
    }

    private void assertEnvelopeShape(String payload, String expectedEventType) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.get("type").asText()).isEqualTo("APP_NOTIFICATION");
        assertThat(json.has("messageId")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("eventType").asText()).isEqualTo(expectedEventType);
        assertThat(json.has("body")).isTrue();
        assertThat(json.has("ttsText")).isTrue();
        assertThat(json.get("priority").asText()).isEqualTo("HIGH");
        // 文档明确要求：envelope 不带 orderId/data 包裹 —— 未来若加 orderId 需同步更新 frontend-guide.md
        assertThat(json.has("orderId")).isFalse();
        assertThat(json.has("data")).isFalse();
    }

    // 订单状态变更结构化消息快照 —— docs/websocket-protocol.md ORDER_STATUS_CHANGED 契约，
    // 此前后端从未实现该 type（仅发 APP_NOTIFICATION 模板通知给盲人），本次补齐盲人+志愿者双发。
    @Test
    void sendOrderStatusChanged_payloadMatchesDocumentedContract() throws Exception {
        notificationService.sendOrderStatusChanged(123L, 100L, 200L,
                "PENDING_ACCEPT", "DRIVER_EN_ROUTE", "志愿者已出发", "志愿者已出发，正在赶往您的位置");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToUser(eq(100L), payload.capture());
        verify(sessionRegistry).sendToUser(eq(200L), payload.capture());
        verify(notificationLogRepository, times(2)).save(any());

        assertOrderStatusChangedShape(payload.getAllValues().get(0), 123L,
                "PENDING_ACCEPT", "DRIVER_EN_ROUTE");
        assertOrderStatusChangedShape(payload.getAllValues().get(1), 123L,
                "PENDING_ACCEPT", "DRIVER_EN_ROUTE");
    }

    @Test
    void sendOrderStatusChanged_nullVolunteerOnlySendsToBlind() {
        notificationService.sendOrderStatusChanged(123L, 100L, null,
                "PENDING_ACCEPT", "DRIVER_EN_ROUTE", "志愿者已出发", "tts");

        verify(sessionRegistry).sendToUser(eq(100L), anyString());
        verifyNoMoreInteractions(sessionRegistry);
    }

    private void assertOrderStatusChangedShape(String payload, long expectedOrderId,
                                               String fromStatus, String toStatus) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.get("type").asText()).isEqualTo("ORDER_STATUS_CHANGED");
        assertThat(json.has("messageId")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("orderId").asLong()).isEqualTo(expectedOrderId);
        assertThat(json.get("fromStatus").asText()).isEqualTo(fromStatus);
        assertThat(json.get("toStatus").asText()).isEqualTo(toStatus);
        assertThat(json.has("message")).isTrue();
        assertThat(json.has("ttsText")).isTrue();
        assertThat(json.get("priority").asText()).isEqualTo("NORMAL");
    }
}

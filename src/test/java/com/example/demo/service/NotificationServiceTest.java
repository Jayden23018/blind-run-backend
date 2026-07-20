package com.example.demo.service;

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
}

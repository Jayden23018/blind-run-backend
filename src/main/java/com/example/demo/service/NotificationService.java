package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.repository.NotificationLogRepository;
import com.example.demo.repository.NotificationTemplateRepository;
import com.example.demo.websocket.UnifiedSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知服务 —— 统一管理 WebSocket 推送、短信、App Push
 * 当前阶段：WebSocket 推送已实现，短信和 Push 为模拟
 */
@Slf4j
@Service
public class NotificationService {

    private final UnifiedSessionRegistry sessionRegistry;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    public NotificationService(UnifiedSessionRegistry sessionRegistry,
                               NotificationLogRepository notificationLogRepository,
                               NotificationTemplateRepository templateRepository,
                               ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.notificationLogRepository = notificationLogRepository;
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 基于模板发送通知 —— 查询模板 → 替换占位符 → 推送 WebSocket
     * @param userId 目标用户 ID
     * @param eventType 事件类型（对应模板 event_type）
     * @param targetRole 目标角色
     * @param params 占位符参数（如 {volunteerName} → params.put("volunteerName", "张三")）
     * @return 解析后的通知文本，未找到模板时返回 null
     */
    @Cacheable(value = "notificationTemplates", key = "#eventType + '_' + #targetRole")
    public String sendNotification(Long userId, String eventType, TargetRole targetRole,
                                   Map<String, String> params) {
        try {
            var templateOpt = templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue(eventType, targetRole);
            if (templateOpt.isEmpty()) {
                log.warn("未找到通知模板: eventType={}, targetRole={}", eventType, targetRole);
                return null;
            }

            NotificationTemplate template = templateOpt.get();
            String text = template.getTemplateText();
            String ttsText = template.getTtsText();

            // 占位符替换
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    String placeholder = "{" + entry.getKey() + "}";
                    text = text.replace(placeholder, entry.getValue());
                    if (ttsText != null) {
                        ttsText = ttsText.replace(placeholder, entry.getValue());
                    }
                }
            }

            // 构建 WebSocket 消息
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "APP_NOTIFICATION");
            msg.put("body", text);
            msg.put("ttsText", ttsText);
            msg.put("priority", template.getPriority().name());
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);
            sessionRegistry.sendToUser(userId, json);

            logNotification(null, userId, NotificationChannel.WEBSOCKET, text);
            return text;
        } catch (Exception e) {
            log.error("发送模板通知失败: eventType={}, userId={}, error={}", eventType, userId, e.getMessage());
            return null;
        }
    }

    /**
     * 发送订单状态变更通知（WebSocket）
     * @deprecated 请使用 sendNotification() 方法替代
     */
    @Deprecated
    public void sendOrderStatusChange(Long orderId, String fromStatus, String toStatus,
                                       Long blindUserId, Long volunteerId, String message) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "ORDER_STATUS_CHANGED");
            msg.put("orderId", orderId);
            msg.put("fromStatus", fromStatus);
            msg.put("toStatus", toStatus);
            msg.put("message", message);
            msg.put("ttsText", message); // 默认使用 message 作为 ttsText
            msg.put("priority", NotificationPriority.NORMAL.name()); // 默认优先级
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);

            if (blindUserId != null) {
                sessionRegistry.sendToUser(blindUserId, json);
            }
            if (volunteerId != null) {
                sessionRegistry.sendToUser(volunteerId, json);
            }

            // 记录通知日志
            logNotification(orderId, blindUserId, NotificationChannel.WEBSOCKET, message);
            if (volunteerId != null) {
                logNotification(orderId, volunteerId, NotificationChannel.WEBSOCKET, message);
            }
        } catch (Exception e) {
            log.error("发送订单状态变更通知失败: {}", e.getMessage());
        }
    }

    /**
     * 发送订单状态变更通知（带 ttsText 和 priority）
     */
    public void sendOrderStatusChange(Long orderId, String fromStatus, String toStatus,
                                       Long blindUserId, Long volunteerId, String message,
                                       String ttsText, NotificationPriority priority) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "ORDER_STATUS_CHANGED");
            msg.put("orderId", orderId);
            msg.put("fromStatus", fromStatus);
            msg.put("toStatus", toStatus);
            msg.put("message", message);
            msg.put("ttsText", ttsText);
            msg.put("priority", priority != null ? priority.name() : NotificationPriority.NORMAL.name());
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);

            if (blindUserId != null) {
                sessionRegistry.sendToUser(blindUserId, json);
            }
            if (volunteerId != null) {
                sessionRegistry.sendToUser(volunteerId, json);
            }

            logNotification(orderId, blindUserId, NotificationChannel.WEBSOCKET, message);
            if (volunteerId != null) {
                logNotification(orderId, volunteerId, NotificationChannel.WEBSOCKET, message);
            }
        } catch (Exception e) {
            log.error("发送订单状态变更通知失败: {}", e.getMessage());
        }
    }

    /**
     * 发送 App 内通知（WebSocket）
     */
    public void sendAppNotification(Long userId, String title, String body) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "APP_NOTIFICATION");
            msg.put("title", title);
            msg.put("body", body);
            msg.put("priority", NotificationPriority.NORMAL.name());
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);
            sessionRegistry.sendToUser(userId, json);

            logNotification(null, userId, NotificationChannel.WEBSOCKET, body);
        } catch (Exception e) {
            log.error("发送 App 通知失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急事件告警（WebSocket → 客服端）
     */
    public void sendEmergencyAlert(EmergencyEvent event) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "EMERGENCY_ALERT");
            msg.put("eventId", event.getId());
            msg.put("userId", event.getUserId());
            msg.put("orderId", event.getOrderId());
            msg.put("gpsLat", event.getGpsLat());
            msg.put("gpsLng", event.getGpsLng());
            msg.put("priority", NotificationPriority.HIGH.name());
            msg.put("triggeredAt", event.getTriggeredAt().toString());

            String json = objectMapper.writeValueAsString(msg);
            sessionRegistry.sendToCs(json);
        } catch (Exception e) {
            log.error("发送紧急事件告警失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急事件告警给志愿者（WebSocket）
     */
    public void sendEmergencyVolunteerAlert(EmergencyEvent event, Long volunteerId) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "EMERGENCY_VOLUNTEER_ALERT");
            msg.put("eventId", event.getId());
            msg.put("orderId", event.getOrderId());
            msg.put("userId", event.getUserId());
            msg.put("message", "您陪伴的盲人用户触发了紧急求助，请在30秒内确认情况");
            msg.put("ttsText", "盲人用户触发了紧急求助，请在30秒内确认情况");
            msg.put("priority", NotificationPriority.HIGH.name());
            msg.put("gpsLat", event.getGpsLat());
            msg.put("gpsLng", event.getGpsLng());
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);
            sessionRegistry.sendToUser(volunteerId, json);

            logNotification(event.getOrderId(), volunteerId, NotificationChannel.WEBSOCKET,
                    "紧急事件志愿者告警");
        } catch (Exception e) {
            log.error("发送紧急事件志愿者告警失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急事件已由志愿者解决通知（WebSocket → 客服 + 盲人）
     */
    public void sendEmergencyResolvedByVolunteer(EmergencyEvent event, boolean needHelp) {
        try {
            // 通知客服
            Map<String, Object> csMsg = new LinkedHashMap<>();
            csMsg.put("type", "EMERGENCY_RESOLVED_BY_VOLUNTEER");
            csMsg.put("eventId", event.getId());
            csMsg.put("orderId", event.getOrderId());
            csMsg.put("resolvedBy", "VOLUNTEER");
            csMsg.put("needHelp", needHelp);
            csMsg.put("priority", NotificationPriority.HIGH.name());
            csMsg.put("timestamp", LocalDateTime.now().toString());
            sessionRegistry.sendToCs(objectMapper.writeValueAsString(csMsg));

            // 通知盲人
            Map<String, Object> blindMsg = new LinkedHashMap<>();
            blindMsg.put("type", "EMERGENCY_RESOLVED_BY_VOLUNTEER");
            blindMsg.put("eventId", event.getId());
            String displayText = needHelp
                    ? "志愿者已确认您需要帮助，正在通知紧急联系人"
                    : "志愿者确认这是一次误触，紧急事件已解除";
            String tts = needHelp
                    ? "志愿者确认你需要帮助，正在通知紧急联系人"
                    : "这是一次误触，紧急事件已解除";
            blindMsg.put("message", displayText);
            blindMsg.put("ttsText", tts);
            blindMsg.put("priority", NotificationPriority.HIGH.name());
            blindMsg.put("timestamp", LocalDateTime.now().toString());
            sessionRegistry.sendToUser(event.getUserId(), objectMapper.writeValueAsString(blindMsg));
        } catch (Exception e) {
            log.error("发送志愿者解决通知失败: {}", e.getMessage());
        }
    }

    /**
     * 发送邻近感知通知（WebSocket）
     */
    public void sendProximityAlert(Long orderId, Long blindUserId, Long volunteerId,
                                    double distanceMeters) {
        try {
            // 通知盲人
            sendNotification(blindUserId, "PROXIMITY_ALERT", TargetRole.BLIND_USER, null);

            // 通知志愿者
            sendNotification(volunteerId, "PROXIMITY_ALERT", TargetRole.VOLUNTEER, null);

            logNotification(orderId, blindUserId, NotificationChannel.WEBSOCKET, "邻近感知通知");
            logNotification(orderId, volunteerId, NotificationChannel.WEBSOCKET, "邻近感知通知");
        } catch (Exception e) {
            log.error("发送邻近感知通知失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急联系人已通知反馈（WebSocket → 盲人）
     */
    public void sendEmergencyContactNotified(Long userId, Long eventId, String contactName) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "EMERGENCY_CONTACT_NOTIFIED");
            msg.put("eventId", eventId);
            msg.put("message", "已通过短信通知您的联系人" + contactName + "，请保持冷静");
            msg.put("ttsText", "已通知你的联系人" + contactName + "，请保持冷静");
            msg.put("priority", NotificationPriority.HIGH.name());
            msg.put("timestamp", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(msg);
            sessionRegistry.sendToUser(userId, json);
        } catch (Exception e) {
            log.error("发送联系人通知反馈失败: {}", e.getMessage());
        }
    }

    /**
     * 发送短信（模拟，当前阶段打印日志）
     */
    public void sendSms(String phone, String content) {
        // TODO: 接入阿里云 SMS
        log.info("【模拟短信】发送至 {}: {}", phone, content);
    }

    /**
     * 发送短信给用户（模拟）
     */
    public void sendSmsToUser(Long userId, String content) {
        log.info("【模拟短信】发送至 userId={}: {}", userId, content);
    }

    // === 私有方法 ===

    private void logNotification(Long orderId, Long userId, NotificationChannel channel, String content) {
        try {
            NotificationLog logEntry = new NotificationLog();
            logEntry.setOrderId(orderId);
            logEntry.setUserId(userId);
            logEntry.setChannel(channel);
            logEntry.setStatus(NotifyStatus.SENT);
            logEntry.setContent(content);
            notificationLogRepository.save(logEntry);
        } catch (Exception e) {
            log.error("记录通知日志失败: {}", e.getMessage());
        }
    }
}

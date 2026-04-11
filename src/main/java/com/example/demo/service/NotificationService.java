package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.repository.NotificationLogRepository;
import com.example.demo.websocket.UnifiedSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
    private final ObjectMapper objectMapper;

    public NotificationService(UnifiedSessionRegistry sessionRegistry,
                               NotificationLogRepository notificationLogRepository,
                               ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.notificationLogRepository = notificationLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送订单状态变更通知（WebSocket）
     */
    public void sendOrderStatusChange(Long orderId, String fromStatus, String toStatus,
                                       Long blindUserId, Long volunteerId, String message) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "ORDER_STATUS_CHANGED");
            msg.put("orderId", orderId);
            msg.put("fromStatus", fromStatus);
            msg.put("toStatus", toStatus);
            msg.put("message", message);
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
     * 发送 App 内通知（WebSocket）
     */
    public void sendAppNotification(Long userId, String title, String body) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "APP_NOTIFICATION");
            msg.put("title", title);
            msg.put("body", body);
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
            csMsg.put("timestamp", LocalDateTime.now().toString());
            sessionRegistry.sendToCs(objectMapper.writeValueAsString(csMsg));

            // 通知盲人
            Map<String, Object> blindMsg = new LinkedHashMap<>();
            blindMsg.put("type", "EMERGENCY_RESOLVED_BY_VOLUNTEER");
            blindMsg.put("eventId", event.getId());
            blindMsg.put("message", needHelp
                    ? "志愿者已确认您需要帮助，正在通知紧急联系人"
                    : "志愿者确认这是一次误触，紧急事件已解除");
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
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PROXIMITY_ALERT");
            msg.put("orderId", orderId);
            msg.put("distanceMeters", Math.round(distanceMeters));
            msg.put("timestamp", LocalDateTime.now().toString());

            // 给盲人发
            Map<String, Object> blindMsg = new LinkedHashMap<>(msg);
            blindMsg.put("message", "您的志愿者已到达附近，如需帮助可点击通话按钮联系他");
            sessionRegistry.sendToUser(blindUserId, objectMapper.writeValueAsString(blindMsg));

            // 给志愿者发
            Map<String, Object> volMsg = new LinkedHashMap<>(msg);
            volMsg.put("message", "您已到达盲人附近，如遇困难可点击通话按钮联系他");
            sessionRegistry.sendToUser(volunteerId, objectMapper.writeValueAsString(volMsg));

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

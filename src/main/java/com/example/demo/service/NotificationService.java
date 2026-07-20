package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.repository.NotificationLogRepository;
import com.example.demo.repository.NotificationTemplateRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.util.PhoneMaskUtils;
import com.example.demo.websocket.UnifiedSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
    private final SmsService smsService;
    private final UserRepository userRepository;

    public NotificationService(UnifiedSessionRegistry sessionRegistry,
                               NotificationLogRepository notificationLogRepository,
                               NotificationTemplateRepository templateRepository,
                               ObjectMapper objectMapper,
                               SmsService smsService,
                               UserRepository userRepository) {
        this.sessionRegistry = sessionRegistry;
        this.notificationLogRepository = notificationLogRepository;
        this.templateRepository = templateRepository;
        this.objectMapper = objectMapper;
        this.smsService = smsService;
        this.userRepository = userRepository;
    }

    /**
     * 基于模板发送通知 —— 查询模板 → 替换占位符 → 推送 WebSocket
     * @param userId 目标用户 ID
     * @param eventType 事件类型（对应模板 event_type）
     * @param targetRole 目标角色
     * @param params 占位符参数（如 {volunteerName} → params.put("volunteerName", "张三")）
     * @return 解析后的通知文本，未找到模板时返回 null
     */
    public String sendNotification(Long userId, String eventType, TargetRole targetRole,
                                   Map<String, String> params) {
        try {
            NotificationTemplate template = getCachedTemplate(eventType, targetRole);
            if (template == null) {
                return null;
            }

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

            Map<String, Object> msg = buildEnvelope("APP_NOTIFICATION");
            msg.put("body", text);
            msg.put("ttsText", ttsText);
            msg.put("priority", template.getPriority().name());

            sessionRegistry.sendToUser(userId, objectMapper.writeValueAsString(msg));

            logNotification(null, userId, NotificationChannel.WEBSOCKET, text);
            return text;
        } catch (Exception e) {
            log.error("发送模板通知失败: eventType={}, userId={}, error={}", eventType, userId, e.getMessage());
            return null;
        }
    }

    /**
     * 向志愿者推送串行派单通知（NEW_ORDER）
     */
    public void sendDispatchNotification(Long volunteerId, RunOrder order,
                                          double distanceKm, int timeoutSeconds) {
        try {
            Map<String, Object> msg = buildEnvelope("NEW_ORDER");
            msg.put("orderId", order.getId());
            msg.put("startAddress", order.getStartAddress());
            msg.put("startLatitude", order.getStartLatitude());
            msg.put("startLongitude", order.getStartLongitude());
            msg.put("distanceKm", Math.round(distanceKm * 10.0) / 10.0);
            msg.put("plannedStart", order.getPlannedStartTime().toString());
            msg.put("plannedEnd", order.getPlannedEndTime().toString());
            msg.put("dispatchTimeoutSeconds", timeoutSeconds);
            msg.put("priority", NotificationPriority.HIGH.name());
            if (order.getPacePreference() != null) {
                msg.put("pacePreference", order.getPacePreference().name());
            }
            if (Boolean.TRUE.equals(order.getHasGuideDogThisRun())) {
                msg.put("hasGuideDog", true);
            }
            if (order.getSpecialNotes() != null && !order.getSpecialNotes().isBlank()) {
                msg.put("specialNotes", order.getSpecialNotes());
            }
            sessionRegistry.sendToUser(volunteerId, objectMapper.writeValueAsString(msg));
            log.info("已向志愿者 {} 推送订单 {} 通知", volunteerId, order.getId());
        } catch (Exception e) {
            log.error("推送订单 {} 通知给志愿者 {} 失败: {}", order.getId(), volunteerId, e.getMessage());
        }
    }

    /**
     * 向盲人推送志愿者实时位置（VOLUNTEER_LOCATION_UPDATE）
     * timestamp 使用 epoch ms 以保持与前端现有协议一致
     */
    public void sendVolunteerLocationUpdate(Long blindUserId, Long orderId,
                                             double lat, double lng) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "VOLUNTEER_LOCATION_UPDATE");
            msg.put("orderId", orderId);
            msg.put("lat", lat);
            msg.put("lng", lng);
            msg.put("timestamp", System.currentTimeMillis());
            sessionRegistry.sendToUser(blindUserId, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("转发志愿者位置给盲人 {} 失败: {}", blindUserId, e.getMessage());
        }
    }

    /**
     * 向志愿者推送盲人实时位置（BLIND_LOCATION_UPDATE）
     * timestamp 使用 epoch ms，与 sendVolunteerLocationUpdate 保持一致
     */
    public void sendBlindLocationUpdate(Long volunteerId, Long orderId, double lat, double lng) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "BLIND_LOCATION_UPDATE");
            msg.put("orderId", orderId);
            msg.put("lat", lat);
            msg.put("lng", lng);
            msg.put("timestamp", System.currentTimeMillis());
            sessionRegistry.sendToUser(volunteerId, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("转发盲人位置给志愿者 {} 失败: {}", volunteerId, e.getMessage());
        }
    }

    /**
     * 发送走散告警（WebSocket → 志愿者 + 盲人）
     */
    public void sendEscortDistanceAlert(Long orderId, Long blindUserId, Long volunteerId) {
        try {
            sendNotification(blindUserId, "ESCORT_DISTANCE_ALERT", TargetRole.BLIND_USER, null);
            sendNotification(volunteerId, "ESCORT_DISTANCE_ALERT", TargetRole.VOLUNTEER, null);
            logNotification(orderId, blindUserId, NotificationChannel.WEBSOCKET, "走散告警");
            logNotification(orderId, volunteerId, NotificationChannel.WEBSOCKET, "走散告警");
        } catch (Exception e) {
            log.error("发送走散告警失败: {}", e.getMessage());
        }
    }

    /**
     * 发送信号缺失告警（WebSocket → 志愿者 + 盲人）—— 对方 GPS 信号连续缺失时的兜底通知，
     * 区别于 sendEscortDistanceAlert（能定位但距离过远），此处根本联系不上对方
     */
    public void sendEscortSignalLostAlert(Long orderId, Long blindUserId, Long volunteerId) {
        try {
            sendNotification(blindUserId, "ESCORT_SIGNAL_LOST", TargetRole.BLIND_USER, null);
            sendNotification(volunteerId, "ESCORT_SIGNAL_LOST", TargetRole.VOLUNTEER, null);
            logNotification(orderId, blindUserId, NotificationChannel.WEBSOCKET, "信号缺失告警");
            logNotification(orderId, volunteerId, NotificationChannel.WEBSOCKET, "信号缺失告警");
        } catch (Exception e) {
            log.error("发送信号缺失告警失败: {}", e.getMessage());
        }
    }

    /**
     * 查询通知模板（带缓存）
     * 缓存 key = eventType + '_' + targetRole，模板更新时 @CacheEvict 清除
     */
    @Cacheable(value = "notificationTemplates", key = "#eventType + '_' + #targetRole")
    public NotificationTemplate getCachedTemplate(String eventType, TargetRole targetRole) {
        return templateRepository.findByEventTypeAndTargetRoleAndIsActiveTrue(eventType, targetRole)
                .orElse(null);
    }

    /**
     * 发送 App 内通知（WebSocket）
     */
    public void sendAppNotification(Long userId, String title, String body) {
        try {
            Map<String, Object> msg = buildEnvelope("APP_NOTIFICATION");
            msg.put("title", title);
            msg.put("body", body);
            msg.put("priority", NotificationPriority.NORMAL.name());

            sessionRegistry.sendToUser(userId, objectMapper.writeValueAsString(msg));
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
            msg.put("hasGpsLocation", event.getGpsLat() != null && event.getGpsLng() != null);
            msg.put("priority", NotificationPriority.HIGH.name());
            msg.put("triggeredAt", event.getTriggeredAt().toString());

            sessionRegistry.sendToCs(objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("发送紧急事件告警失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急事件告警给志愿者（WebSocket）
     */
    public void sendEmergencyVolunteerAlert(EmergencyEvent event, Long volunteerId) {
        try {
            Map<String, Object> msg = buildEnvelope("EMERGENCY_VOLUNTEER_ALERT");
            msg.put("eventId", event.getId());
            msg.put("orderId", event.getOrderId());
            msg.put("userId", event.getUserId());
            msg.put("message", "您陪伴的盲人用户触发了紧急求助，请在30秒内确认情况");
            msg.put("ttsText", "盲人用户触发了紧急求助，请在30秒内确认情况");
            msg.put("priority", NotificationPriority.HIGH.name());
            msg.put("gpsLat", event.getGpsLat());
            msg.put("gpsLng", event.getGpsLng());

            sessionRegistry.sendToUser(volunteerId, objectMapper.writeValueAsString(msg));
            logNotification(event.getOrderId(), volunteerId, NotificationChannel.WEBSOCKET, "紧急事件志愿者告警");
        } catch (Exception e) {
            log.error("发送紧急事件志愿者告警失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急事件已由志愿者解决通知（WebSocket → 客服 + 盲人）
     */
    public void sendEmergencyResolvedByVolunteer(EmergencyEvent event, boolean needHelp) {
        try {
            Map<String, Object> csMsg = buildEnvelope("EMERGENCY_RESOLVED_BY_VOLUNTEER");
            csMsg.put("eventId", event.getId());
            csMsg.put("orderId", event.getOrderId());
            csMsg.put("resolvedBy", "VOLUNTEER");
            csMsg.put("needHelp", needHelp);
            csMsg.put("priority", NotificationPriority.HIGH.name());
            sessionRegistry.sendToCs(objectMapper.writeValueAsString(csMsg));

            String displayText = needHelp
                    ? "志愿者已确认您需要帮助，正在通知紧急联系人"
                    : "志愿者确认这是一次误触，紧急事件已解除";
            String tts = needHelp
                    ? "志愿者已确认你需要帮助，正在通知紧急联系人"
                    : "志愿者确认这是误触，紧急事件已解除。若仍需帮助请重新触发";
            Map<String, Object> blindMsg = buildEnvelope("EMERGENCY_RESOLVED_BY_VOLUNTEER");
            blindMsg.put("eventId", event.getId());
            blindMsg.put("message", displayText);
            blindMsg.put("ttsText", tts);
            blindMsg.put("priority", NotificationPriority.HIGH.name());
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
            sendNotification(blindUserId, "PROXIMITY_ALERT", TargetRole.BLIND_USER, null);
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
            Map<String, Object> msg = buildEnvelope("EMERGENCY_CONTACT_NOTIFIED");
            msg.put("eventId", eventId);
            msg.put("message", "已通过短信通知您的联系人" + contactName + "，请保持冷静");
            msg.put("ttsText", "已通知你的联系人" + contactName + "，请保持冷静，帮助正在路上");
            msg.put("priority", NotificationPriority.HIGH.name());

            sessionRegistry.sendToUser(userId, objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("发送联系人通知反馈失败: {}", e.getMessage());
        }
    }

    /**
     * 发送紧急求助短信给联系人
     */
    public void sendEmergencyAlertSms(String phone, String userName, String time, String location) {
        Map<String, String> params = new HashMap<>();
        params.put("user_name", userName);
        params.put("time", time);
        params.put("location", location != null ? location : "未知");
        smsService.sendTemplateSms(phone, SmsTemplate.EMERGENCY_ALERT, params);
    }

    /**
     * 发送紧急联系人被添加通知短信
     */
    public void sendContactAddedSms(String phone, String userName) {
        smsService.sendTemplateSms(phone, SmsTemplate.CONTACT_ADDED, Map.of("user_name", userName));
    }

    /**
     * 发送紧急求助解除通知短信
     */
    public void sendEmergencyResolvedSms(String phone, String userName, String time) {
        smsService.sendTemplateSms(phone, SmsTemplate.EMERGENCY_RESOLVED,
                Map.of("user_name", userName, "time", time));
    }

    // === 私有方法 ===

    /** 构建消息信封：预填 type、去重用 messageId 和 ISO-8601 timestamp */
    private Map<String, Object> buildEnvelope(String type) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("timestamp", LocalDateTime.now().toString());
        return msg;
    }

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

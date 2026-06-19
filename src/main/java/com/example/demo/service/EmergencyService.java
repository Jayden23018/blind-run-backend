package com.example.demo.service;

import com.example.demo.dto.EmergencyTriggerRequest;
import com.example.demo.entity.*;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 紧急事件服务 —— 处理盲人触发紧急事件的完整流程
 *
 * 流程：触发 → 通知志愿者 + 写入 volunteer_timeout_at → 定时轮询检测超时 → 志愿者确认/超时 → 通知联系人/客服
 */
@Slf4j
@Service
public class EmergencyService {

    /** 紧急联系人短信通知内容（复用常量） */
    private static final String EMERGENCY_CONTACT_SMS = "紧急通知：您的亲友在使用助盲跑服务时触发了紧急求助，请尽快联系。";

    private final EmergencyEventRepository eventRepository;
    private final EmergencyNotificationRepository notificationRepository;
    private final RunOrderRepository runOrderRepository;
    private final EmergencyContactRepository contactRepository;
    private final CSUserRepository csUserRepository;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final GeocodingService geocodingService;

    @Value("${app.emergency.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Value("${app.emergency.volunteer-timeout-seconds:30}")
    private int volunteerTimeoutSeconds;

    public EmergencyService(EmergencyEventRepository eventRepository,
                            EmergencyNotificationRepository notificationRepository,
                            RunOrderRepository runOrderRepository,
                            EmergencyContactRepository contactRepository,
                            CSUserRepository csUserRepository,
                            NotificationService notificationService,
                            StringRedisTemplate redisTemplate,
                            UserRepository userRepository,
                            GeocodingService geocodingService) {
        this.eventRepository = eventRepository;
        this.notificationRepository = notificationRepository;
        this.runOrderRepository = runOrderRepository;
        this.contactRepository = contactRepository;
        this.csUserRepository = csUserRepository;
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.geocodingService = geocodingService;
    }

    /**
     * 触发紧急事件
     */
    @Transactional
    public EmergencyEvent triggerEmergency(Long userId, EmergencyTriggerRequest request) {
        // 1. 冷却检查（使用 SETNX 原子操作，避免竞态条件）
        String cooldownKey = "emergency:cooldown:" + userId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", cooldownSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("紧急事件触发过于频繁，请稍后再试");
        }

        // 2. 校验订单归属（orderId 可选：进行中订单触发时校验参与者；独立 SOS 不传 orderId）
        Long orderId = request.getOrderId();
        RunOrder order = null;
        if (orderId != null) {
            order = runOrderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
            boolean isBlind = order.getBlindUser().getId().equals(userId);
            boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);
            if (!isBlind && !isVolunteer) {
                throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "您无权操作此订单");
            }
        }

        // 3. 创建紧急事件
        EmergencyEvent event = new EmergencyEvent();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setTriggerType(TriggerType.BUTTON);
        event.setStatus(EmergencyStatus.PENDING);
        event.setGpsLat(request.getGpsLat());
        event.setGpsLng(request.getGpsLng());

        eventRepository.save(event);

        // 4. 冷却 key 已在步骤 1 中通过 setIfAbsent 设置

        // 5. 通知志愿者 + 发短信给盲人
        handleEmergencyTriggered(event, order);

        log.warn("紧急事件已触发! eventId={}, userId={}, orderId={}", event.getId(), userId, orderId);
        return event;
    }

    /**
     * 志愿者响应紧急事件
     */
    @Transactional
    public void handleVolunteerResponse(Long eventId, Long volunteerId, VolunteerAction action) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在"));

        // 校验操作者是订单的志愿者
        RunOrder order = runOrderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "只有该订单的志愿者才能响应");
        }

        if (event.getStatus() != EmergencyStatus.VOLUNTEER_NOTIFIED) {
            throw new IllegalStateException("当前状态不允许志愿者响应");
        }

        event.setVolunteerConfirmedAt(LocalDateTime.now());
        event.setVolunteerAction(action);

        if (action == VolunteerAction.FALSE_ALARM) {
            // 误触 → 直接解决
            event.setStatus(EmergencyStatus.FALSE_ALARM);
            event.setResolvedAt(LocalDateTime.now());
            eventRepository.save(event);

            notificationService.sendEmergencyResolvedByVolunteer(event, false);
            log.info("志愿者 {} 确认误触, eventId={}", volunteerId, eventId);
        } else {
            // 需要帮助 → 通知紧急联系人 + 推送客服
            event.setStatus(EmergencyStatus.VOLUNTEER_CONFIRMED);
            eventRepository.save(event);

            escalateToEmergencyContacts(event);
            log.info("志愿者 {} 确认需要帮助, eventId={}", volunteerId, eventId);
        }
    }

    /**
     * 志愿者超时未响应（由 TimeoutScheduler 轮询调用）
     */
    @Transactional
    public void handleVolunteerTimeout(Long eventId) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> {
                    log.warn("超时处理时事件不存在, eventId={}", eventId);
                    return new IllegalArgumentException("事件不存在");
                });

        // 状态校验：只处理还在 VOLUNTEER_NOTIFIED 状态的事件
        if (event.getStatus() != EmergencyStatus.VOLUNTEER_NOTIFIED) {
            log.debug("事件状态已变更，跳过超时处理, eventId={}, status={}", eventId, event.getStatus());
            return;
        }

        log.warn("志愿者超时未响应, eventId={}，进入严重处理", eventId);

        // 通知盲人用户：志愿者未响应，已升级处理
        notificationService.sendNotification(event.getUserId(), "EMERGENCY_VOLUNTEER_TIMEOUT",
                TargetRole.BLIND_USER, null);

        // 超时视为严重 → 直接通知紧急联系人
        escalateToEmergencyContacts(event);
    }

    /**
     * 客服获取待处理事件
     */
    public List<EmergencyEvent> getPendingEvents() {
        return eventRepository.findByStatusIn(
                List.of(EmergencyStatus.PENDING, EmergencyStatus.VOLUNTEER_NOTIFIED,
                        EmergencyStatus.VOLUNTEER_CONFIRMED, EmergencyStatus.CONTACT_NOTIFIED));
    }

    /**
     * 客服接手
     */
    @Transactional
    public void acceptEvent(Long eventId, Long csUserId) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在"));

        event.setStatus(EmergencyStatus.CS_HANDLING);
        event.setCsUserId(csUserId);
        eventRepository.save(event);

        log.info("客服 {} 已接手紧急事件 {}", csUserId, eventId);
    }

    /**
     * 通知紧急联系人
     */
    @Transactional
    public void notifyContact(Long eventId, Long csUserId) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在"));

        EmergencyContact primaryContact = contactRepository
                .findByUserIdAndIsPrimaryTrue(event.getUserId())
                .orElseThrow(() -> new IllegalStateException("未设置主要紧急联系人"));

        EmergencyNotification notification = new EmergencyNotification();
        notification.setEventId(eventId);
        notification.setContactId(primaryContact.getId());
        notification.setNotifyType(NotifyType.SMS_TO_CONTACT);
        notification.setStatus(NotifyStatus.SENT);
        notification.setContent(EMERGENCY_CONTACT_SMS);
        notificationRepository.save(notification);

        event.setStatus(EmergencyStatus.CONTACT_NOTIFIED);
        event.setCsUserId(csUserId);
        eventRepository.save(event);

        // 发送紧急求助模板短信
        User triggerUser = userRepository.findById(event.getUserId())
                .orElseThrow(() -> new IllegalStateException("触发用户不存在"));
        notificationService.sendEmergencyAlertSms(
                primaryContact.getPhone(),
                triggerUser.getName(),
                event.getTriggeredAt().toString(),
                formatLocation(event));

        log.info("已通知紧急联系人, eventId={}, contactId={}", eventId, primaryContact.getId());
    }

    /**
     * 标记已解决
     */
    @Transactional
    public void resolveEvent(Long eventId, Long csUserId, String notes) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在"));

        event.setStatus(EmergencyStatus.RESOLVED);
        event.setCsUserId(csUserId);
        event.setCsNotes(notes);
        event.setResolvedAt(LocalDateTime.now());
        eventRepository.save(event);

        // 通知紧急联系人：紧急求助已解除
        contactRepository.findByUserIdAndIsPrimaryTrue(event.getUserId()).ifPresent(contact -> {
            User user = userRepository.findById(event.getUserId()).orElse(null);
            notificationService.sendEmergencyResolvedSms(
                    contact.getPhone(),
                    user != null ? user.getName() : "未知用户",
                    LocalDateTime.now().toString());
        });

        log.info("紧急事件已解决, eventId={}, csUserId={}", eventId, csUserId);
    }

    /**
     * 标记误触
     */
    @Transactional
    public void markFalseAlarm(Long eventId, Long csUserId) {
        EmergencyEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在"));

        event.setStatus(EmergencyStatus.FALSE_ALARM);
        event.setCsUserId(csUserId);
        event.setResolvedAt(LocalDateTime.now());
        eventRepository.save(event);

        log.info("紧急事件标记为误触, eventId={}, csUserId={}", eventId, csUserId);
    }

    // === 私有方法 ===

    /**
     * 触发后即时处理：WebSocket 通知盲人 + 推送告警给志愿者 + 写入超时时间
     */
    private void handleEmergencyTriggered(EmergencyEvent event, RunOrder order) {
        if (order != null && order.getVolunteer() != null) {
            // 1a. 有志愿者：通知志愿者，等待响应
            event.setStatus(EmergencyStatus.VOLUNTEER_NOTIFIED);
            event.setVolunteerNotifiedAt(LocalDateTime.now());
            event.setVolunteerTimeoutAt(LocalDateTime.now().plusSeconds(volunteerTimeoutSeconds));
            eventRepository.save(event);

            // 通知盲人：已收到求助，正在通知志愿者
            Map<String, String> params = new HashMap<>();
            params.put("volunteerName", order.getVolunteer().getName());
            notificationService.sendNotification(event.getUserId(), "EMERGENCY_TRIGGERED",
                    TargetRole.BLIND_USER, params);
            notificationService.sendEmergencyVolunteerAlert(event, order.getVolunteer().getId());
            log.info("已通知志愿者，等待 {} 秒响应, eventId={}", volunteerTimeoutSeconds, event.getId());
        } else {
            // 1b. 无订单（独立 SOS）或订单未匹配到志愿者：通知盲人后直接升级
            eventRepository.save(event);
            // 通知盲人：已收到求助，正在紧急处理
            notificationService.sendNotification(event.getUserId(), "EMERGENCY_TRIGGERED",
                    TargetRole.BLIND_USER, null);
            log.warn("紧急事件无关联志愿者（独立 SOS 或订单未匹配），直接升级处理, eventId={}", event.getId());
            escalateToEmergencyContacts(event);
        }

        // 2. 推送客服
        notificationService.sendEmergencyAlert(event);
    }

    /**
     * 升级处理：通知紧急联系人（严重情况）
     */
    private void escalateToEmergencyContacts(EmergencyEvent event) {
        EmergencyContact primaryContact = contactRepository
                .findByUserIdAndIsPrimaryTrue(event.getUserId()).orElse(null);
        if (primaryContact != null) {
            User triggerUser = userRepository.findById(event.getUserId()).orElse(null);
            String userName = triggerUser != null ? triggerUser.getName() : "未知用户";
            NotifyStatus smsStatus = NotifyStatus.SENT;
            try {
                notificationService.sendEmergencyAlertSms(
                        primaryContact.getPhone(),
                        userName,
                        event.getTriggeredAt().toString(),
                        formatLocation(event));
            } catch (Exception e) {
                smsStatus = NotifyStatus.FAILED;
                log.error("紧急联系人短信发送失败，eventId={}, phone={}: {}",
                        event.getId(), primaryContact.getPhone(), e.getMessage());
            }

            EmergencyNotification notification = new EmergencyNotification();
            notification.setEventId(event.getId());
            notification.setContactId(primaryContact.getId());
            notification.setNotifyType(NotifyType.SMS_TO_CONTACT);
            notification.setStatus(smsStatus);
            notification.setContent("紧急事件升级，自动通知紧急联系人");
            notificationRepository.save(notification);

            event.setStatus(EmergencyStatus.CONTACT_NOTIFIED);
            eventRepository.save(event);

            // 通知盲人用户
            Map<String, String> params = new HashMap<>();
            params.put("contactName", primaryContact.getName());
            notificationService.sendNotification(event.getUserId(), "EMERGENCY_CONTACT_NOTIFIED",
                    TargetRole.BLIND_USER, params);
        } else {
            log.warn("未找到主要紧急联系人, userId={}", event.getUserId());
            // S5：盲人无紧急联系人时明确反馈，避免"正在通知家人"的承诺落空（用户无感知）
            notificationService.sendNotification(event.getUserId(),
                    "EMERGENCY_NO_CONTACT", TargetRole.BLIND_USER, null);
            // 推进到 CS_HANDLING：避免事件卡在 VOLUNTEER_NOTIFIED 被 TimeoutScheduler 重复 escalate
            event.setStatus(EmergencyStatus.CS_HANDLING);
            eventRepository.save(event);
        }
    }

    /**
     * 格式化紧急位置，用于紧急联系人短信（受阿里云短信单变量 ≤35 字符限制）。
     * 三级降级保证短信永远合规可发：文字地址 → 可读经纬度 → 求助引导语。
     */
    private String formatLocation(EmergencyEvent event) {
        BigDecimal lat = event.getGpsLat();
        BigDecimal lng = event.getGpsLng();
        if (lat == null || lng == null) {
            return "位置获取失败，请尽快拨打其电话或报警110";
        }
        // 优先：逆地理编码为文字地址（家人最易理解、最能快速定位）
        String address = geocodingService.reverseGeocode(lat, lng).orElse(null);
        if (address != null && !address.isBlank()) {
            // 阿里云短信单变量上限 35 字符，保守截断到 30
            return address.length() > 30 ? address.substring(0, 30) : address;
        }
        // 降级：可读经纬度（保留 5 位小数 ≈1 米精度，串长 <35 字符，家人可手动输入地图）
        return "纬度" + trimCoord(lat) + " 经度" + trimCoord(lng);
    }

    /** 坐标保留 5 位小数，避免短信变量过长 */
    private String trimCoord(BigDecimal coord) {
        return coord.setScale(5, RoundingMode.HALF_UP).toPlainString();
    }
}

package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.event.DispatchAcceptedEvent;
import com.example.demo.event.OrderCreatedEvent;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.exception.OrderStatusException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单生命周期服务 —— 状态机流转、超时处理、派单接受
 *
 * 状态机：PENDING_MATCH → PENDING_ACCEPT → IN_PROGRESS → DRIVER_EN_ROUTE → DRIVER_ARRIVED → COMPLETED
 * 超时：REMATCHING（rematch）、PENDING_MATCH（match）、IN_PROGRESS overdue
 */
@Slf4j
@Service
public class OrderLifecycleService {

    private final RunOrderRepository runOrderRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final OrderStatusLogService statusLogService;
    private final NotificationService notificationService;
    private final ProximityService proximityService;
    private final VolunteerLocationService volunteerLocationService;

    @Value("${app.rematch.timeout-seconds:300}")
    private long rematchTimeoutSeconds;

    @Value("${app.match.timeout-seconds:300}")
    private long matchTimeoutSeconds;

    private static final int MAX_MATCH_NOTIFY_COUNT = 3;

    /** 志愿者取消导致的最大重新匹配次数，超过则自动终止订单，避免无限轮转 */
    private static final int MAX_REMATCH_COUNT = 3;

    public OrderLifecycleService(RunOrderRepository runOrderRepository,
                                 UserRepository userRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 VolunteerProfileRepository volunteerProfileRepository,
                                 OrderStatusLogService statusLogService,
                                 NotificationService notificationService,
                                 ProximityService proximityService,
                                 VolunteerLocationService volunteerLocationService) {
        this.runOrderRepository = runOrderRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.statusLogService = statusLogService;
        this.notificationService = notificationService;
        this.proximityService = proximityService;
        this.volunteerLocationService = volunteerLocationService;
    }

    // ===== 志愿者操作 =====

    @Transactional
    public void acceptOrder(Long orderId, Long volunteerId) {
        VolunteerProfile profile = volunteerProfileRepository.findByUserId(volunteerId)
                .orElseThrow(() -> new OrderPermissionException("VOLUNTEER_NOT_VERIFIED", "请先完成志愿者认证"));

        if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
            throw new OrderPermissionException("VOLUNTEER_NOT_REGISTERED", "请先完成志愿者注册流程（当前步骤：" +
                    profile.getRegistrationStep().name() + "）");
        }
        if (!Boolean.TRUE.equals(profile.getVerified())) {
            throw new OrderPermissionException("VOLUNTEER_NOT_VERIFIED", "请先完成志愿者认证");
        }

        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_MATCH
                && order.getStatus() != OrderStatus.REMATCHING) {
            throw new OrderStatusException("订单已被其他志愿者接单或已取消");
        }

        String oldStatus = order.getStatus().name();
        order.setVolunteer(userRepository.getReferenceById(volunteerId));
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAcceptedAt(LocalDateTime.now());
        order.setRematchNotifyAt(null);
        order.setMatchNotifyAt(null);
        runOrderRepository.save(order);

        String eventType = "REMATCHING".equals(oldStatus) ? "REMATCH_ACCEPTED" : "ORDER_ACCEPTED";
        notifyBlindUser(order.getBlindUser().getId(), eventType, volunteerId);
        statusLogService.logStatusChange(orderId, oldStatus, "IN_PROGRESS", volunteerId, "志愿者接单");

        log.info("志愿者 {} 已接单，订单ID={}，原状态={}", volunteerId, orderId, oldStatus);
    }

    // acceptOrderWithRetry / rejectOrder 已删除：B2 修复后 /accept、/reject 改走
    // DispatchService.handleVolunteerResponse，这两个方法无生产调用点。acceptOrder 保留供单测。

    @Transactional
    public void driverEnRoute(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "只有接单的志愿者才能操作");
        }
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new OrderStatusException("当前订单状态不允许此操作");
        }

        String oldStatus = order.getStatus().name();
        order.setStatus(OrderStatus.DRIVER_EN_ROUTE);
        runOrderRepository.save(order);

        statusLogService.logStatusChange(orderId, oldStatus, "DRIVER_EN_ROUTE", volunteerId, "志愿者已出发");
        notifyBlindUser(order.getBlindUser().getId(), "DRIVER_EN_ROUTE", volunteerId);

        log.info("志愿者 {} 已出发，订单ID={}", volunteerId, orderId);
    }

    @Transactional
    public void driverArrived(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "只有接单的志愿者才能操作");
        }
        if (order.getStatus() != OrderStatus.DRIVER_EN_ROUTE) {
            throw new OrderStatusException("当前订单状态不允许此操作");
        }

        String oldStatus = order.getStatus().name();
        order.setStatus(OrderStatus.DRIVER_ARRIVED);
        runOrderRepository.save(order);

        statusLogService.logStatusChange(orderId, oldStatus, "DRIVER_ARRIVED", volunteerId, "志愿者已到达");
        notifyBlindUser(order.getBlindUser().getId(), "DRIVER_ARRIVED", volunteerId);

        log.info("志愿者 {} 已到达，订单ID={}", volunteerId, orderId);
    }

    @Transactional
    public void finishOrder(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "只有接单的志愿者才能结束服务");
        }
        if (order.getStatus() != OrderStatus.IN_PROGRESS
                && order.getStatus() != OrderStatus.DRIVER_EN_ROUTE
                && order.getStatus() != OrderStatus.DRIVER_ARRIVED) {
            throw new OrderStatusException("订单不在服务中状态");
        }

        String oldStatus = order.getStatus().name();
        order.setStatus(OrderStatus.COMPLETED);
        order.setFinishedAt(LocalDateTime.now());
        runOrderRepository.save(order);

        proximityService.clearProximityFlag(orderId);
        statusLogService.logStatusChange(orderId, oldStatus, "COMPLETED", volunteerId, "服务完成");
        notificationService.sendNotification(order.getBlindUser().getId(), "ORDER_COMPLETED", TargetRole.BLIND_USER, null);

        log.info("志愿者 {} 结束了订单 {}，订单完成", volunteerId, orderId);
    }

    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);

        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "您无权操作此订单");
        }

        OrderStatus status = order.getStatus();

        if (isBlind) {
            if (status == OrderStatus.IN_PROGRESS || status == OrderStatus.DRIVER_EN_ROUTE
                    || status == OrderStatus.DRIVER_ARRIVED) {
                throw new OrderPermissionException("ORDER_IN_PROGRESS", "服务进行中，如需结束请联系志愿者");
            }
            if (status != OrderStatus.PENDING_MATCH && status != OrderStatus.PENDING_ACCEPT
                    && status != OrderStatus.REMATCHING) {
                throw new OrderStatusException("当前订单状态不允许取消");
            }
            order.setCancelledBy(CancelledBy.BLIND);

            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            order.setRematchNotifyAt(null);
            order.setMatchNotifyAt(null);
            runOrderRepository.save(order);

            statusLogService.logStatusChange(orderId, oldStatus, "CANCELLED", userId,
                    "取消方=" + order.getCancelledBy());
            proximityService.clearProximityFlag(orderId);
            log.info("订单 {} 已取消，取消方={}，原状态={}", orderId, order.getCancelledBy(), status);

        } else {
            if (status != OrderStatus.PENDING_ACCEPT && status != OrderStatus.IN_PROGRESS
                    && status != OrderStatus.DRIVER_EN_ROUTE && status != OrderStatus.DRIVER_ARRIVED) {
                throw new OrderStatusException("当前订单状态不允许取消");
            }

            // 重新匹配已达上限：不再重派，直接终止订单（参照 handleMatchTimeout 的自动取消模式），
            // 避免志愿者持续取消导致订单无限轮转、盲人永远等不到终态
            int currentRematch = order.getRematchCount() != null ? order.getRematchCount() : 0;
            if (currentRematch >= MAX_REMATCH_COUNT) {
                String oldStatus = order.getStatus().name();
                order.setVolunteer(null);
                order.setCancelledBy(CancelledBy.SYSTEM);
                order.setStatus(OrderStatus.CANCELLED);
                order.setRematchNotifyAt(null);
                runOrderRepository.save(order);

                statusLogService.logStatusChange(orderId, oldStatus, "CANCELLED", userId,
                        "重新匹配超过" + MAX_REMATCH_COUNT + "次，自动取消");
                notificationService.sendNotification(order.getBlindUser().getId(),
                        "ORDER_AUTO_CANCELLED", TargetRole.BLIND_USER, null);
                proximityService.clearProximityFlag(orderId);
                log.warn("订单 {} 重新匹配已达上限 {} 次，自动取消", orderId, MAX_REMATCH_COUNT);
                return;
            }

            // 任何阶段志愿者取消均进入 REMATCHING，给盲人重新匹配机会
            String oldStatus = order.getStatus().name();
            order.setVolunteer(null);
            order.setCancelledBy(CancelledBy.VOLUNTEER);
            order.setRematchCount(order.getRematchCount() != null ? order.getRematchCount() + 1 : 1);
            order.setLastRematchAt(LocalDateTime.now());
            order.setRematchNotifyAt(LocalDateTime.now().plusSeconds(rematchTimeoutSeconds));
            order.setStatus(OrderStatus.REMATCHING);
            runOrderRepository.save(order);

            statusLogService.logStatusChange(orderId, oldStatus, "REMATCHING", userId,
                    "志愿者取消，进入重新匹配，第" + order.getRematchCount() + "次");
            notificationService.sendNotification(order.getBlindUser().getId(), "REMATCHING", TargetRole.BLIND_USER, null);
            eventPublisher.publishEvent(new OrderCreatedEvent(this, order));
            proximityService.clearProximityFlag(orderId);
            log.info("订单 {} 志愿者取消 → REMATCHING，原状态={}，第{}次重新匹配",
                    orderId, oldStatus, order.getRematchCount());
        }
    }

    @Transactional
    public void keepWaiting(Long orderId, Long blindUserId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在，ID: " + orderId));

        if (!order.getBlindUser().getId().equals(blindUserId)) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "只有订单的盲人用户才能操作");
        }
        if (order.getStatus() != OrderStatus.PENDING_MATCH) {
            throw new IllegalStateException("当前订单状态不支持此操作");
        }

        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("盲人 {} 选择继续等待，订单 {} 提醒时间已刷新", blindUserId, orderId);
    }

    // ===== 超时处理 =====

    @Transactional
    public void handleRematchTimeout(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.REMATCHING) return;

        notificationService.sendNotification(order.getBlindUser().getId(), "REMATCH_TIMEOUT", TargetRole.BLIND_USER, null);
        order.setRematchNotifyAt(LocalDateTime.now().plusSeconds(rematchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("订单 {} 重新匹配超时提醒已发送给盲人 {}", orderId, order.getBlindUser().getId());
    }

    @Transactional
    public void handleMatchTimeout(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_MATCH) return;

        int notifyCount = order.getMatchNotifyCount() != null ? order.getMatchNotifyCount() : 0;
        notifyCount++;

        if (notifyCount > MAX_MATCH_NOTIFY_COUNT) {
            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancelledBy(CancelledBy.SYSTEM);
            order.setMatchNotifyAt(null);
            runOrderRepository.save(order);
            statusLogService.logStatusChange(orderId, oldStatus, "CANCELLED", null, "匹配超时自动取消");
            notificationService.sendNotification(order.getBlindUser().getId(), "ORDER_AUTO_CANCELLED",
                    TargetRole.BLIND_USER, null);
            log.info("订单 {} 匹配超时已超过 {} 次，自动取消", orderId, MAX_MATCH_NOTIFY_COUNT);
            return;
        }

        notificationService.sendNotification(order.getBlindUser().getId(), "NO_VOLUNTEER_AVAILABLE",
                TargetRole.BLIND_USER, null);
        order.setMatchNotifyCount(notifyCount);
        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("订单 {} 匹配超时提醒已发送给盲人 {}（第 {}/{} 次）",
                orderId, order.getBlindUser().getId(), notifyCount, MAX_MATCH_NOTIFY_COUNT);
    }

    @Transactional
    public void handleOverdueOrder(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithUsers(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.IN_PROGRESS) return;
        if (Boolean.TRUE.equals(order.getOverdueNotified())) return;

        if (order.getVolunteer() != null) {
            notificationService.sendNotification(order.getVolunteer().getId(), "ORDER_OVERDUE", TargetRole.VOLUNTEER, null);
        }
        order.setOverdueNotified(true);
        runOrderRepository.save(order);

        log.info("订单 {} 已超过结束时间1小时，已通知志愿者", orderId);
    }

    // ===== 事件监听 =====

    @EventListener
    @Async
    @Transactional
    public void onDispatchAccepted(DispatchAcceptedEvent event) {
        Long orderId = event.getOrderId();
        Long volunteerId = event.getVolunteerId();

        RunOrder order = runOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_ACCEPT) {
            log.warn("订单 {} 状态异常，跳过接单处理（期望 PENDING_ACCEPT，实际 {}）",
                    orderId, order != null ? order.getStatus() : "不存在");
            return;
        }

        order.setVolunteer(userRepository.getReferenceById(volunteerId));
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAcceptedAt(LocalDateTime.now());
        order.setMatchNotifyAt(null);
        runOrderRepository.save(order);

        statusLogService.logStatusChange(orderId, OrderStatus.PENDING_ACCEPT.name(), "IN_PROGRESS",
                volunteerId, "串行派单接单");
        notifyBlindUser(order.getBlindUser().getId(), "VOLUNTEER_ACCEPTED", volunteerId);

        log.info("志愿者 {} 通过派单接单，订单 {} → IN_PROGRESS", volunteerId, orderId);
    }

    // ===== 私有辅助方法 =====

    private String getVolunteerName(Long volunteerId) {
        return userRepository.findById(volunteerId).map(User::getName).orElse("志愿者");
    }

    private void notifyBlindUser(Long blindUserId, String eventType, Long volunteerId) {
        Map<String, String> params = new HashMap<>();
        params.put("volunteerName", getVolunteerName(volunteerId));
        notificationService.sendNotification(blindUserId, eventType, TargetRole.BLIND_USER, params);
    }
}

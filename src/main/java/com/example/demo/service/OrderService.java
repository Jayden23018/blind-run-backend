package com.example.demo.service;

import com.example.demo.dto.CreateOrderRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.entity.*;
import com.example.demo.event.OrderCreatedEvent;
import com.example.demo.exception.DuplicateOrderException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.exception.OrderStatusException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.util.GeoUtils;
import com.example.demo.util.PhoneMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单业务逻辑服务 —— 处理订单的创建、接单、拒单、完成、取消
 * 支持扩展状态机：PENDING_MATCH → PENDING_ACCEPT → IN_PROGRESS → DRIVER_EN_ROUTE → DRIVER_ARRIVED → COMPLETED
 */
@Slf4j
@Service
public class OrderService {

    private final RunOrderRepository runOrderRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BlindProfileRepository blindProfileRepository;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final OrderStatusLogService statusLogService;
    private final EmergencyContactService emergencyContactService;
    private final NotificationService notificationService;
    private final ProximityService proximityService;
    private final DispatchService dispatchService;

    @Value("${app.matching.max-distance-km:10}")
    private double maxDistanceKm;

    /** 重新匹配超时时间（秒） */
    @Value("${app.rematch.timeout-seconds:300}")
    private long rematchTimeoutSeconds;

    /** 匹配超时提醒间隔（秒） */
    @Value("${app.match.timeout-seconds:300}")
    private long matchTimeoutSeconds;

    /** 匹配超时最大提醒次数（超过后自动取消订单） */
    private static final int MAX_MATCH_NOTIFY_COUNT = 3;

    public OrderService(RunOrderRepository runOrderRepository,
                        UserRepository userRepository,
                        ApplicationEventPublisher eventPublisher,
                        BlindProfileRepository blindProfileRepository,
                        VolunteerProfileRepository volunteerProfileRepository,
                        OrderStatusLogService statusLogService,
                        EmergencyContactService emergencyContactService,
                        NotificationService notificationService,
                        ProximityService proximityService,
                        DispatchService dispatchService) {
        this.runOrderRepository = runOrderRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.blindProfileRepository = blindProfileRepository;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.statusLogService = statusLogService;
        this.emergencyContactService = emergencyContactService;
        this.notificationService = notificationService;
        this.proximityService = proximityService;
        this.dispatchService = dispatchService;
    }

    /**
     * 创建订单
     */
    @Transactional
    public OrderResponse createOrder(Long blindUserId, CreateOrderRequest request) {
        // 1. 校验时间
        if (!request.getPlannedEndTime().isAfter(request.getPlannedStartTime())) {
            throw new IllegalArgumentException("计划结束时间必须晚于开始时间");
        }
        if (!request.getPlannedStartTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("计划开始时间不能早于当前时间");
        }

        // 2. 校验重复订单
        boolean hasActiveOrder = runOrderRepository.existsByBlindUserIdAndStatusIn(
                blindUserId,
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.IN_PROGRESS,
                        OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED, OrderStatus.REMATCHING)
        );
        if (hasActiveOrder) {
            throw new DuplicateOrderException("您有进行中的订单，请完成后再下单");
        }

        // 3. 校验紧急联系人
        if (!emergencyContactService.hasContacts(blindUserId)) {
            throw new OrderPermissionException("请先设置紧急联系人再下单");
        }

        // 4. 创建并保存订单
        RunOrder order = new RunOrder();
        order.setBlindUser(userRepository.getReferenceById(blindUserId));
        order.setStartLatitude(request.getStartLatitude());
        order.setStartLongitude(request.getStartLongitude());
        order.setStartAddress(request.getStartAddress());
        order.setPlannedStartTime(request.getPlannedStartTime());
        order.setPlannedEndTime(request.getPlannedEndTime());
        order.setStatus(OrderStatus.PENDING_MATCH);

        // 设置特殊需求字段（部分从盲人档案回退默认值）
        BlindProfile profile = blindProfileRepository.findByUserId(blindUserId).orElse(null);

        order.setExpectedDurationMinutes(request.getExpectedDurationMinutes());

        order.setPacePreference(request.getPacePreference() != null
                ? request.getPacePreference()
                : (profile != null ? profile.getDefaultPace() : null));

        order.setRoutePreference(request.getRoutePreference() != null
                ? request.getRoutePreference()
                : RoutePreference.NO_PREFERENCE);

        order.setRouteNotes(request.getRouteNotes());

        order.setHasGuideDogThisRun(request.getHasGuideDogThisRun() != null
                ? request.getHasGuideDogThisRun()
                : (profile != null ? profile.getHasGuideDog() : null));

        order.setSpecialNotes(request.getSpecialNotes());

        // 设置匹配超时提醒时间
        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));

        RunOrder savedOrder = runOrderRepository.save(order);

        // 5. 记录状态日志
        statusLogService.logStatusChange(savedOrder.getId(), null, "PENDING_MATCH", blindUserId, "创建订单");

        // 6. 发布订单创建事件
        eventPublisher.publishEvent(new OrderCreatedEvent(this, savedOrder));

        log.info("订单已创建，ID={}，盲人用户={}，状态=PENDING_MATCH", savedOrder.getId(), blindUserId);

        return new OrderResponse(savedOrder.getId(), savedOrder.getStatus(), "订单已提交，正在匹配志愿者");
    }

    /**
     * 志愿者接单
     * 支持 PENDING_MATCH / PENDING_ACCEPT / REMATCHING 状态
     */
    @Transactional
    public void acceptOrder(Long orderId, Long volunteerId) {
        VolunteerProfile profile = volunteerProfileRepository.findByUserId(volunteerId)
                .orElseThrow(() -> new OrderPermissionException("请先完成志愿者认证"));

        // 新增：检查注册步骤
        if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
            throw new OrderPermissionException("请先完成志愿者注册流程（当前步骤：" +
                profile.getRegistrationStep().name() + "）");
        }

        if (!Boolean.TRUE.equals(profile.getVerified())) {
            throw new OrderPermissionException("请先完成志愿者认证");
        }

        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_MATCH
                && order.getStatus() != OrderStatus.PENDING_ACCEPT
                && order.getStatus() != OrderStatus.REMATCHING) {
            throw new OrderStatusException("订单已被其他志愿者接单或已取消");
        }

        String oldStatus = order.getStatus().name();
        order.setVolunteer(userRepository.getReferenceById(volunteerId));
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAcceptedAt(LocalDateTime.now());
        // 清除 rematchNotifyAt，不再需要超时提醒
        order.setRematchNotifyAt(null);
        // 清除 matchNotifyAt，不再需要匹配超时提醒
        order.setMatchNotifyAt(null);

        runOrderRepository.save(order);

        // 通知盲人用户
        String eventType = "REMATCHING".equals(oldStatus) ? "REMATCH_ACCEPTED" : "ORDER_ACCEPTED";
        notifyBlindUser(order.getBlindUser().getId(), eventType, volunteerId);

        // 记录日志
        statusLogService.logStatusChange(orderId, oldStatus, "IN_PROGRESS", volunteerId, "志愿者接单");

        log.info("志愿者 {} 已接单，订单ID={}，原状态={}", volunteerId, orderId, oldStatus);
    }

    /**
     * 志愿者接单（带乐观锁重试）
     * 如果多个志愿者同时接单，只有一个能成功
     */
    @Transactional
    public void acceptOrderWithRetry(Long orderId, Long volunteerId) {
        try {
            acceptOrder(orderId, volunteerId);
        } catch (OptimisticLockingFailureException e) {
            log.warn("志愿者 {} 接单 {} 时发生并发冲突，订单可能已被其他志愿者接走", volunteerId, orderId);
            throw new OrderStatusException("订单已被其他志愿者接单，请刷新后重试");
        }
    }

    /**
     * 志愿者拒单
     */
    public void rejectOrder(Long orderId, Long volunteerId) {
        log.info("志愿者 {} 拒绝了订单 {}", volunteerId, orderId);
    }

    /**
     * 志愿者确认出发
     */
    @Transactional
    public void driverEnRoute(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("只有接单的志愿者才能操作");
        }
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new OrderStatusException("当前订单状态不允许此操作");
        }

        String oldStatus = order.getStatus().name();
        order.setStatus(OrderStatus.DRIVER_EN_ROUTE);
        runOrderRepository.save(order);

        statusLogService.logStatusChange(orderId, oldStatus, "DRIVER_EN_ROUTE", volunteerId, "志愿者已出发");

        // 通知盲人用户
        notifyBlindUser(order.getBlindUser().getId(), "DRIVER_EN_ROUTE", volunteerId);

        log.info("志愿者 {} 已出发，订单ID={}", volunteerId, orderId);
    }

    /**
     * 志愿者确认到达
     */
    @Transactional
    public void driverArrived(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("只有接单的志愿者才能操作");
        }
        if (order.getStatus() != OrderStatus.DRIVER_EN_ROUTE) {
            throw new OrderStatusException("当前订单状态不允许此操作");
        }

        String oldStatus = order.getStatus().name();
        order.setStatus(OrderStatus.DRIVER_ARRIVED);
        runOrderRepository.save(order);

        statusLogService.logStatusChange(orderId, oldStatus, "DRIVER_ARRIVED", volunteerId, "志愿者已到达");

        // 通知盲人用户
        notifyBlindUser(order.getBlindUser().getId(), "DRIVER_ARRIVED", volunteerId);

        log.info("志愿者 {} 已到达，订单ID={}", volunteerId, orderId);
    }

    /**
     * 志愿者结束服务
     */
    @Transactional
    public void finishOrder(Long orderId, Long volunteerId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getVolunteer() == null || !order.getVolunteer().getId().equals(volunteerId)) {
            throw new OrderPermissionException("只有接单的志愿者才能结束服务");
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

        // 清除邻近感知标记
        proximityService.clearProximityFlag(orderId);

        statusLogService.logStatusChange(orderId, oldStatus, "COMPLETED", volunteerId, "服务完成");

        // 通知盲人用户
        notificationService.sendNotification(order.getBlindUser().getId(), "ORDER_COMPLETED", TargetRole.BLIND_USER, null);

        log.info("志愿者 {} 结束了订单 {}，订单完成", volunteerId, orderId);
    }

    /**
     * 取消订单
     *
     * 盲人取消 → CANCELLED
     * 志愿者在 IN_PROGRESS 取消 → CANCELLED（服务已开始，记录爽约）
     * 志愿者在 PENDING_ACCEPT / DRIVER_EN_ROUTE / DRIVER_ARRIVED 取消 → REMATCHING（重新匹配）
     */
    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);

        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("您无权操作此订单");
        }

        OrderStatus status = order.getStatus();

        if (isBlind) {
            // 盲人取消：PENDING_MATCH / PENDING_ACCEPT / REMATCHING 均可取消
            if (status == OrderStatus.IN_PROGRESS || status == OrderStatus.DRIVER_EN_ROUTE
                    || status == OrderStatus.DRIVER_ARRIVED) {
                throw new OrderPermissionException("服务进行中，如需结束请联系志愿者");
            }
            if (status != OrderStatus.PENDING_MATCH && status != OrderStatus.PENDING_ACCEPT
                    && status != OrderStatus.REMATCHING) {
                throw new OrderStatusException("当前订单状态不允许取消");
            }
            order.setCancelledBy(CancelledBy.BLIND);

            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            // 清除超时提醒时间
            order.setRematchNotifyAt(null);
            order.setMatchNotifyAt(null);

            runOrderRepository.save(order);

            statusLogService.logStatusChange(orderId, oldStatus, "CANCELLED", userId,
                    "取消方=" + order.getCancelledBy());

            log.info("订单 {} 已取消，取消方={}，原状态={}", orderId, order.getCancelledBy(), status);

        } else {
            // 志愿者取消
            if (status != OrderStatus.PENDING_ACCEPT && status != OrderStatus.IN_PROGRESS
                    && status != OrderStatus.DRIVER_EN_ROUTE && status != OrderStatus.DRIVER_ARRIVED) {
                throw new OrderStatusException("当前订单状态不允许取消");
            }

            if (status == OrderStatus.IN_PROGRESS) {
                // IN_PROGRESS 阶段取消 → 直接 CANCELLED（服务已开始）
                order.setCancelledBy(CancelledBy.VOLUNTEER);
                String oldStatus = order.getStatus().name();
                order.setStatus(OrderStatus.CANCELLED);
                runOrderRepository.save(order);
                statusLogService.logStatusChange(orderId, oldStatus, "CANCELLED", userId,
                        "取消方=" + order.getCancelledBy());
                // 通知盲人用户（志愿者取消服务）
                notificationService.sendNotification(order.getBlindUser().getId(), "VOLUNTEER_CANCELLED", TargetRole.BLIND_USER, null);
                log.info("订单 {} 已取消（IN_PROGRESS），取消方=VOLUNTEER", orderId);
            } else {
                // PENDING_ACCEPT / DRIVER_EN_ROUTE / DRIVER_ARRIVED → REMATCHING
                String oldStatus = order.getStatus().name();

                // 清除当前志愿者绑定
                order.setVolunteer(null);
                order.setCancelledBy(CancelledBy.VOLUNTEER);

                // 更新重新匹配计数
                order.setRematchCount(order.getRematchCount() != null ? order.getRematchCount() + 1 : 1);
                order.setLastRematchAt(LocalDateTime.now());

                // 设置下次提醒时间
                order.setRematchNotifyAt(LocalDateTime.now().plusSeconds(rematchTimeoutSeconds));

                order.setStatus(OrderStatus.REMATCHING);
                runOrderRepository.save(order);

                // 记录日志 + 通知盲人
                statusLogService.logStatusChange(orderId, oldStatus, "REMATCHING", userId,
                        "志愿者取消，进入重新匹配，第" + order.getRematchCount() + "次");
                notificationService.sendNotification(order.getBlindUser().getId(), "REMATCHING", TargetRole.BLIND_USER, null);

                // 重新推入匹配队列
                eventPublisher.publishEvent(new OrderCreatedEvent(this, order));

                log.info("订单 {} 志愿者取消 → REMATCHING，原状态={}，第{}次重新匹配",
                        orderId, oldStatus, order.getRematchCount());
            }
        }
    }

    /**
     * 处理重新匹配超时 —— 由 TimeoutScheduler 轮询调用
     * 状态校验 + 推送提醒 + 更新下次提醒时间（循环）
     */
    @Transactional
    public void handleRematchTimeout(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.REMATCHING) {
            return;
        }

        // 推送提醒给盲人
        notificationService.sendNotification(order.getBlindUser().getId(), "REMATCH_TIMEOUT", TargetRole.BLIND_USER, null);

        // 更新下次提醒时间（循环提醒）
        order.setRematchNotifyAt(LocalDateTime.now().plusSeconds(rematchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("订单 {} 重新匹配超时提醒已发送给盲人 {}", orderId, order.getBlindUser().getId());
    }

    /**
     * 查询订单详情（需校验权限）
     */
    public RunOrder getOrder(Long orderId, Long userId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);

        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("您无权查看此订单");
        }

        return order;
    }

    /**
     * 查询附近可接订单列表（志愿者视角）
     */
    public List<AvailableOrderResponse> getAvailableOrders(Long volunteerId, double volunteerLat, double volunteerLng) {
        List<RunOrder> pendingOrders = runOrderRepository.findByStatusIn(
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.REMATCHING));

        List<AvailableOrderResponse> result = new ArrayList<>();
        for (RunOrder order : pendingOrders) {
            double distance = GeoUtils.distanceKm(
                    volunteerLat, volunteerLng,
                    order.getStartLatitude(), order.getStartLongitude()
            );
            if (distance <= maxDistanceKm) {
                result.add(new AvailableOrderResponse(
                        order.getId(),
                        order.getStartAddress(),
                        Math.round(distance * 10.0) / 10.0,
                        order.getPlannedStartTime(),
                        order.getPlannedEndTime(),
                        PhoneMaskUtils.mask(order.getBlindUser().getPhone()),
                        order.getExpectedDurationMinutes(),
                        order.getPacePreference(),
                        order.getHasGuideDogThisRun(),
                        order.getSpecialNotes()
                ));
            }
        }

        result.sort((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()));
        return result.stream().limit(20).toList();
    }

    /**
     * 处理匹配超时提醒 —— 由 TimeoutScheduler 轮询调用
     * 校验状态 + 推送提醒给盲人 + 更新下次提醒时间（循环）
     */
    @Transactional
    public void handleMatchTimeout(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_MATCH) {
            return;
        }

        int notifyCount = order.getMatchNotifyCount() != null ? order.getMatchNotifyCount() : 0;
        notifyCount++;

        if (notifyCount > MAX_MATCH_NOTIFY_COUNT) {
            // 超过最大提醒次数，自动取消订单
            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancelledBy(CancelledBy.BLIND);
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

        // 更新提醒计数和下次提醒时间
        order.setMatchNotifyCount(notifyCount);
        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("订单 {} 匹配超时提醒已发送给盲人 {}（第 {}/{} 次）",
                orderId, order.getBlindUser().getId(), notifyCount, MAX_MATCH_NOTIFY_COUNT);
    }

    /**
     * 处理超时挂起订单 —— 由 TimeoutScheduler 轮询调用
     * 超过结束时间1小时的进行中订单，提醒志愿者确认
     */
    @Transactional
    public void handleOverdueOrder(Long orderId) {
        RunOrder order = runOrderRepository.findByIdWithUsers(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.IN_PROGRESS) {
            return;
        }
        if (Boolean.TRUE.equals(order.getOverdueNotified())) {
            return;
        }

        // 通知志愿者
        if (order.getVolunteer() != null) {
            notificationService.sendNotification(order.getVolunteer().getId(), "ORDER_OVERDUE", TargetRole.VOLUNTEER, null);
        }

        order.setOverdueNotified(true);
        runOrderRepository.save(order);

        log.info("订单 {} 已超过结束时间1小时，已通知志愿者", orderId);
    }

    /**
     * 盲人继续等待 —— 刷新匹配超时提醒时间
     */
    @Transactional
    public void keepWaiting(Long orderId, Long blindUserId) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在，ID: " + orderId));

        if (!order.getBlindUser().getId().equals(blindUserId)) {
            throw new OrderPermissionException("只有订单的盲人用户才能操作");
        }
        if (order.getStatus() != OrderStatus.PENDING_MATCH) {
            throw new IllegalStateException("当前订单状态不支持此操作");
        }

        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));
        runOrderRepository.save(order);

        log.info("盲人 {} 选择继续等待，订单 {} 提醒时间已刷新", blindUserId, orderId);
    }

    /**
     * 附近可接订单响应 record
     */
    public record AvailableOrderResponse(Long orderId, String startAddress, double distanceKm,
                                          LocalDateTime plannedStart, LocalDateTime plannedEnd,
                                          String blindUserPhone,
                                          Integer expectedDurationMinutes,
                                          PacePreference pacePreference,
                                          Boolean hasGuideDogThisRun,
                                          String specialNotes) {}

    // === 私有辅助方法 ===

    /**
     * 获取志愿者名字（用于通知消息）
     */
    private String getVolunteerName(Long volunteerId) {
        return userRepository.findById(volunteerId).map(User::getName).orElse("志愿者");
    }

    /**
     * 通知盲人用户（包含志愿者名称参数的统一方法）
     */
    private void notifyBlindUser(Long blindUserId, String eventType, Long volunteerId) {
        Map<String, String> params = new HashMap<>();
        params.put("volunteerName", getVolunteerName(volunteerId));
        notificationService.sendNotification(blindUserId, eventType, TargetRole.BLIND_USER, params);
    }

    /**
     * 志愿者响应派单（接单或跳过）
     */
    @Transactional
    public void respondToDispatchOrder(Long orderId, Long volunteerId, com.example.demo.dto.RespondAction action) {
        RunOrder order = runOrderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_MATCH) {
            throw new OrderStatusException("订单状态不允许响应: " + order.getStatus());
        }

        switch (action) {
            case ACCEPT -> {
                dispatchService.handleAccept(orderId, volunteerId);
                // 接单成功后走现有流程：状态已是 PENDING_ACCEPT，通知盲人
                RunOrder updated = runOrderRepository.findById(orderId).orElse(null);
                if (updated != null && updated.getVolunteer() == null) {
                    // 设置志愿者并转 IN_PROGRESS（复用现有 acceptOrder 逻辑）
                    User volunteer = userRepository.getReferenceById(volunteerId);
                    updated.setVolunteer(volunteer);
                    updated.setStatus(OrderStatus.IN_PROGRESS);
                    updated.setAcceptedAt(LocalDateTime.now());
                    runOrderRepository.save(updated);

                    statusLogService.logStatusChange(orderId,
                            OrderStatus.PENDING_MATCH.name(),
                            OrderStatus.IN_PROGRESS.name(),
                            volunteerId, "串行派单接单");

                    notifyBlindUser(updated.getBlindUser().getId(), "VOLUNTEER_ACCEPTED", volunteerId);
                    log.info("志愿者 {} 通过派单接单，订单 {} → IN_PROGRESS", volunteerId, orderId);
                }
            }
            case DECLINE -> dispatchService.handleDecline(orderId, volunteerId);
        }
    }
}

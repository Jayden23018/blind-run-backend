package com.example.demo.service;

import com.example.demo.dto.CreateOrderRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.entity.*;
import com.example.demo.event.OrderCreatedEvent;
import com.example.demo.exception.DuplicateOrderException;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.exception.OrderTooSoonException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单创建服务 —— 仅负责创建新订单
 */
@Slf4j
@Service
public class OrderCreationService {

    private final RunOrderRepository runOrderRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BlindProfileRepository blindProfileRepository;
    private final EmergencyContactService emergencyContactService;
    private final OrderStatusLogService statusLogService;

    @Value("${app.match.timeout-seconds:300}")
    private long matchTimeoutSeconds;

    /** 预约开始时间距当前时间的最小提前量（分钟） */
    @Value("${app.order.min-lead-time-minutes:30}")
    private int minLeadTimeMinutes;

    public OrderCreationService(RunOrderRepository runOrderRepository,
                                UserRepository userRepository,
                                ApplicationEventPublisher eventPublisher,
                                BlindProfileRepository blindProfileRepository,
                                EmergencyContactService emergencyContactService,
                                OrderStatusLogService statusLogService) {
        this.runOrderRepository = runOrderRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.blindProfileRepository = blindProfileRepository;
        this.emergencyContactService = emergencyContactService;
        this.statusLogService = statusLogService;
    }

    @Transactional
    public OrderResponse createOrder(Long blindUserId, CreateOrderRequest request) {
        if (!request.getPlannedEndTime().isAfter(request.getPlannedStartTime())) {
            throw new IllegalArgumentException("计划结束时间必须晚于开始时间");
        }
        if (!request.getPlannedStartTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("计划开始时间不能早于当前时间");
        }
        // 提前量校验：预约开始时间必须距当前时间至少 minLeadTimeMinutes 分钟，
        // 否则没有足够时间完成派单流程（5→10→20km 三轮扩圈，每轮含志愿者响应超时）。
        if (request.getPlannedStartTime().isBefore(LocalDateTime.now().plusMinutes(minLeadTimeMinutes))) {
            throw new OrderTooSoonException(
                    "预约开始时间需距当前时间至少 " + minLeadTimeMinutes + " 分钟");
        }

        boolean hasActiveOrder = runOrderRepository.existsByBlindUserIdAndStatusIn(
                blindUserId,
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.IN_PROGRESS,
                        OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED, OrderStatus.REMATCHING)
        );
        if (hasActiveOrder) {
            throw new DuplicateOrderException("您有进行中的订单，请完成后再下单");
        }

        if (!emergencyContactService.hasContacts(blindUserId)) {
            throw new OrderPermissionException("请先设置紧急联系人再下单");
        }

        RunOrder order = new RunOrder();
        order.setBlindUser(userRepository.getReferenceById(blindUserId));
        order.setStartLatitude(request.getStartLatitude());
        order.setStartLongitude(request.getStartLongitude());
        order.setStartAddress(request.getStartAddress());
        order.setPlannedStartTime(request.getPlannedStartTime());
        order.setPlannedEndTime(request.getPlannedEndTime());
        order.setStatus(OrderStatus.PENDING_MATCH);

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
        order.setMatchNotifyAt(LocalDateTime.now().plusSeconds(matchTimeoutSeconds));

        RunOrder savedOrder = runOrderRepository.save(order);

        statusLogService.logStatusChange(savedOrder.getId(), null, "PENDING_MATCH", blindUserId, "创建订单");
        eventPublisher.publishEvent(new OrderCreatedEvent(this, savedOrder));

        log.info("订单已创建，ID={}，盲人用户={}，状态=PENDING_MATCH", savedOrder.getId(), blindUserId);

        return new OrderResponse(savedOrder.getId(), savedOrder.getStatus(), "订单已提交，正在匹配志愿者");
    }
}

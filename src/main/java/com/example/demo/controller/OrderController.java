package com.example.demo.controller;

import com.example.demo.dto.AvailableOrderResponse;
import com.example.demo.dto.CreateOrderRequest;
import com.example.demo.dto.DispatchRespondRequest;
import com.example.demo.dto.OrderDetailResponse;
import com.example.demo.dto.OrderResponse;
import com.example.demo.dto.OrderStatusLogResponse;
import com.example.demo.dto.RespondAction;
import com.example.demo.entity.BlindProfile;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.User;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.DispatchService;
import com.example.demo.service.OrderCreationService;
import com.example.demo.service.OrderLifecycleService;
import com.example.demo.service.OrderQueryService;
import com.example.demo.service.OrderStatusLogService;
import com.example.demo.service.VolunteerLocationService;

import com.example.demo.util.PhoneMaskUtils;
import com.example.demo.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单控制器 —— 处理订单相关的 HTTP 请求
 *
 * POST   /api/orders                  → 盲人创建订单
 * POST   /api/orders/{id}/accept      → 志愿者接单
 * POST   /api/orders/{id}/reject      → 志愿者拒单
 * POST   /api/orders/{id}/respond     → 志愿者响应派单（串行派单专用）
 * POST   /api/orders/{id}/en-route    → 志愿者确认出发
 * POST   /api/orders/{id}/arrived     → 志愿者确认到达
 * POST   /api/orders/{id}/finish      → 志愿者结束服务
 * POST   /api/orders/{id}/cancel      → 取消订单
 * PUT    /api/orders/{id}/keep-waiting → 盲人继续等待
 * GET    /api/orders/available        → 附近可接订单列表
 * GET    /api/orders/{id}             → 查询订单详情
 * GET    /api/orders/mine             → 查询我的订单列表
 * GET    /api/orders/{id}/status-logs → 获取状态变更历史
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {

    private final OrderCreationService orderCreationService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderQueryService orderQueryService;
    private final DispatchService dispatchService;
    private final OrderStatusLogService statusLogService;
    private final RunOrderRepository runOrderRepository;
    private final VolunteerLocationService volunteerLocationService;
    private final UserRepository userRepository;
    private final BlindProfileRepository blindProfileRepository;

    public OrderController(OrderCreationService orderCreationService,
                           OrderLifecycleService orderLifecycleService,
                           OrderQueryService orderQueryService,
                           DispatchService dispatchService,
                           OrderStatusLogService statusLogService,
                           RunOrderRepository runOrderRepository,
                           VolunteerLocationService volunteerLocationService,
                           UserRepository userRepository,
                           BlindProfileRepository blindProfileRepository) {
        this.orderCreationService = orderCreationService;
        this.orderLifecycleService = orderLifecycleService;
        this.orderQueryService = orderQueryService;
        this.dispatchService = dispatchService;
        this.statusLogService = statusLogService;
        this.runOrderRepository = runOrderRepository;
        this.volunteerLocationService = volunteerLocationService;
        this.userRepository = userRepository;
        this.blindProfileRepository = blindProfileRepository;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        OrderResponse response = orderCreationService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * @deprecated 已由 POST /{id}/respond 替代（串行派单专用）。
     *             保留此接口仅为向后兼容旧版前端，新客户端请使用 /respond。
     *             （B2 修复：现已复用 /respond 的派单归属校验，行为与 /respond 完全一致——
     *              仅当前被派单的志愿者可接单，杜绝绕过串行派单协议抢单。）
     */
    @Deprecated
    @PostMapping("/{id}/accept")
    public ResponseEntity<?> acceptOrder(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        dispatchService.handleVolunteerResponse(id, userId, RespondAction.ACCEPT);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    /**
     * @deprecated 已由 POST /{id}/respond 替代（串行派单专用）。
     *             保留此接口仅为向后兼容旧版前端，新客户端请使用 /respond。
     *             （B2 修复：原 rejectOrder 是空操作、不推进派单队列；现复用 /respond 的
     *              DECLINE 逻辑，正确派给下一位志愿者。）
     */
    @Deprecated
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectOrder(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        dispatchService.handleVolunteerResponse(id, userId, RespondAction.DECLINE);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 志愿者响应派单（接单或跳过）—— 串行派单专用 */
    @PostMapping("/{id}/respond")
    public ResponseEntity<?> respondToDispatch(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody DispatchRespondRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        dispatchService.handleVolunteerResponse(id, userId, request.action());
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    /** 志愿者确认出发 */
    @PostMapping("/{id}/en-route")
    public ResponseEntity<?> driverEnRoute(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderLifecycleService.driverEnRoute(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    /** 志愿者确认到达 */
    @PostMapping("/{id}/arrived")
    public ResponseEntity<?> driverArrived(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderLifecycleService.driverArrived(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<?> finishOrder(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderLifecycleService.finishOrder(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderLifecycleService.cancelOrder(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 盲人选择继续等待匹配 */
    @PutMapping("/{id}/keep-waiting")
    public ResponseEntity<?> keepWaiting(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderLifecycleService.keepWaiting(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/available")
    public ResponseEntity<?> getAvailableOrders() {
        Long userId = SecurityUtils.getCurrentUserId();

        Map<String, Object> loc = volunteerLocationService.getVolunteerLocation(userId);
        if (loc == null) {
            return ResponseEntity.ok(List.of());
        }

        double lat = ((Number) loc.get("lat")).doubleValue();
        double lng = ((Number) loc.get("lng")).doubleValue();
        List<AvailableOrderResponse> orders = orderQueryService.getAvailableOrders(userId, lat, lng);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailResponse> getOrder(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        RunOrder order = orderQueryService.getOrder(id, userId);
        return ResponseEntity.ok(toDetailResponse(order));
    }

    @GetMapping("/mine")
    public ResponseEntity<Page<OrderDetailResponse>> getMyOrders(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {

        Long userId = SecurityUtils.getCurrentUserId();

        if (role == null || role.isBlank()) {
            User user = userRepository.findById(userId).orElseThrow();
            role = user.getRole().name();
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RunOrder> orders;

        if ("BLIND".equalsIgnoreCase(role)) {
            if (status != null) {
                OrderStatus orderStatus = parseOrderStatus(status);
                orders = runOrderRepository.findByBlindUserIdAndStatusIn(
                        userId, List.of(orderStatus), pageable);
            } else {
                orders = runOrderRepository.findByBlindUserId(userId, pageable);
            }
        } else if ("VOLUNTEER".equalsIgnoreCase(role)) {
            if (status != null) {
                OrderStatus orderStatus = parseOrderStatus(status);
                orders = runOrderRepository.findByVolunteerIdAndStatusIn(
                        userId, List.of(orderStatus), pageable);
            } else {
                orders = runOrderRepository.findByVolunteerId(userId, pageable);
            }
        } else {
            throw new IllegalArgumentException("无效的角色参数，仅支持 BLIND 或 VOLUNTEER");
        }

        Map<Long, BlindProfile> profileMap = batchLoadProfiles(orders);
        return ResponseEntity.ok(orders.map(o -> toDetailResponse(o, profileMap)));
    }

    /** 获取状态变更历史 */
    @GetMapping("/{id}/status-logs")
    public ResponseEntity<List<OrderStatusLogResponse>> getStatusLogs(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();

        RunOrder order = runOrderRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);
        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("NOT_ORDER_PARTICIPANT", "您无权查看此订单");
        }

        List<OrderStatusLogResponse> logs = statusLogService.getStatusLogs(id).stream()
                .map(log -> {
                    OrderStatusLogResponse resp = new OrderStatusLogResponse();
                    resp.setId(log.getId());
                    resp.setOrderId(log.getOrderId());
                    resp.setFromStatus(log.getFromStatus());
                    resp.setToStatus(log.getToStatus());
                    resp.setChangedBy(log.getChangedBy());
                    resp.setChangedAt(log.getChangedAt());
                    resp.setRemark(log.getRemark());
                    return resp;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(logs);
    }

    private OrderStatus parseOrderStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的订单状态: " + status);
        }
    }

    private Map<Long, BlindProfile> batchLoadProfiles(Page<RunOrder> orders) {
        List<Long> blindUserIds = orders.getContent().stream()
                .map(o -> o.getBlindUser().getId())
                .distinct()
                .toList();
        if (blindUserIds.isEmpty()) {
            return Map.of();
        }
        return blindProfileRepository.findByUserIdIn(blindUserIds).stream()
                .collect(java.util.stream.Collectors.toMap(bp -> bp.getUser().getId(), bp -> bp));
    }

    private OrderDetailResponse toDetailResponse(RunOrder order, Map<Long, BlindProfile> profileMap) {
        String volunteerPhone = order.getVolunteer() != null ? PhoneMaskUtils.mask(order.getVolunteer().getPhone()) : null;
        BlindProfile blindProfile = order.getBlindUser() != null ? profileMap.get(order.getBlindUser().getId()) : null;
        return new OrderDetailResponse(
                order.getId(),
                order.getStatus(),
                order.getStartAddress(),
                order.getPlannedStartTime(),
                order.getPlannedEndTime(),
                volunteerPhone,
                order.getAcceptedAt(),
                order.getCreatedAt(),
                order.getExpectedDurationMinutes(),
                order.getPacePreference(),
                order.getRoutePreference(),
                order.getRouteNotes(),
                order.getHasGuideDogThisRun(),
                order.getSpecialNotes(),
                blindProfile != null ? blindProfile.getVisionLevel() : null,
                blindProfile != null ? blindProfile.getTetherPreference() : null,
                blindProfile != null ? blindProfile.getChatPreference() : null
        );
    }

    private OrderDetailResponse toDetailResponse(RunOrder order) {
        String volunteerPhone = order.getVolunteer() != null ? PhoneMaskUtils.mask(order.getVolunteer().getPhone()) : null;
        BlindProfile blindProfile = order.getBlindUser() != null
                ? blindProfileRepository.findByUserId(order.getBlindUser().getId()).orElse(null) : null;
        return new OrderDetailResponse(
                order.getId(),
                order.getStatus(),
                order.getStartAddress(),
                order.getPlannedStartTime(),
                order.getPlannedEndTime(),
                volunteerPhone,
                order.getAcceptedAt(),
                order.getCreatedAt(),
                order.getExpectedDurationMinutes(),
                order.getPacePreference(),
                order.getRoutePreference(),
                order.getRouteNotes(),
                order.getHasGuideDogThisRun(),
                order.getSpecialNotes(),
                blindProfile != null ? blindProfile.getVisionLevel() : null,
                blindProfile != null ? blindProfile.getTetherPreference() : null,
                blindProfile != null ? blindProfile.getChatPreference() : null
        );
    }
}

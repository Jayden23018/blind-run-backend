package com.example.demo.controller;

import com.example.demo.dto.CreateOrderRequest;
import com.example.demo.dto.DispatchRespondRequest;
import com.example.demo.dto.OrderDetailResponse;
import com.example.demo.dto.OrderResponse;
import com.example.demo.entity.BlindProfile;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.User;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.OrderService;
import com.example.demo.service.VolunteerLocationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.example.demo.util.PhoneMaskUtils;

import java.util.List;
import java.util.Map;

/**
 * 订单控制器 —— 处理订单相关的 HTTP 请求
 *
 * POST   /api/orders              → 盲人创建订单
 * POST   /api/orders/{id}/accept  → 志愿者接单
 * POST   /api/orders/{id}/reject  → 志愿者拒单
 * POST   /api/orders/{id}/finish  → 志愿者结束服务
 * POST   /api/orders/{id}/cancel  → 取消订单
 * GET    /api/orders/{id}         → 查询订单详情
 * GET    /api/orders/mine         → 查询我的订单列表
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final RunOrderRepository runOrderRepository;
    private final VolunteerLocationService volunteerLocationService;
    private final UserRepository userRepository;
    private final BlindProfileRepository blindProfileRepository;

    public OrderController(OrderService orderService, RunOrderRepository runOrderRepository,
                           VolunteerLocationService volunteerLocationService,
                           UserRepository userRepository,
                           BlindProfileRepository blindProfileRepository) {
        this.orderService = orderService;
        this.runOrderRepository = runOrderRepository;
        this.volunteerLocationService = volunteerLocationService;
        this.userRepository = userRepository;
        this.blindProfileRepository = blindProfileRepository;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> acceptOrder(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.acceptOrderWithRetry(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectOrder(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.rejectOrder(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 志愿者响应派单（接单或跳过）—— 串行派单专用
     */
    @PostMapping("/{id}/respond")
    public ResponseEntity<?> respondToDispatch(
            @PathVariable Long id,
            @Valid @RequestBody DispatchRespondRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.respondToDispatchOrder(id, userId, request.action());
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<?> finishOrder(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.finishOrder(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.cancelOrder(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 盲人选择继续等待匹配 */
    @PutMapping("/{id}/keep-waiting")
    public ResponseEntity<?> keepWaiting(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        orderService.keepWaiting(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/available")
    public ResponseEntity<?> getAvailableOrders() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // 获取志愿者最新位置
        double lat = 0, lng = 0;
        boolean hasLocation = false;
        for (var loc : volunteerLocationService.getOnlineVolunteerLocations()) {
            if (((Number) loc.get("userId")).longValue() == userId) {
                lat = ((Number) loc.get("lat")).doubleValue();
                lng = ((Number) loc.get("lng")).doubleValue();
                hasLocation = true;
                break;
            }
        }
        if (!hasLocation) {
            return ResponseEntity.ok(List.of());
        }

        List<OrderService.AvailableOrderResponse> orders = orderService.getAvailableOrders(userId, lat, lng);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailResponse> getOrder(@PathVariable Long id) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        RunOrder order = orderService.getOrder(id, userId);
        return ResponseEntity.ok(toDetailResponse(order));
    }

    @GetMapping("/mine")
    public ResponseEntity<Page<OrderDetailResponse>> getMyOrders(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // 不传 role 时，自动从用户信息获取
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

        return ResponseEntity.ok(orders.map(this::toDetailResponse));
    }

    /** 安全解析订单状态枚举，无效值返回中文错误 */
    private OrderStatus parseOrderStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的订单状态: " + status);
        }
    }

    /** 将 RunOrder 实体转换为 OrderDetailResponse DTO */
    private OrderDetailResponse toDetailResponse(RunOrder order) {
        String volunteerPhone = order.getVolunteer() != null ? PhoneMaskUtils.mask(order.getVolunteer().getPhone()) : null;
        // 从盲人档案获取冗余字段
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

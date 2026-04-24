package com.example.demo.controller;

import com.example.demo.dto.OrderStatusLogResponse;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.OrderService;
import com.example.demo.service.OrderStatusLogService;
import com.example.demo.util.SecurityUtils;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单状态变更控制器 — 新增状态流转端点
 * GET  /api/orders/{id}/status-logs — 获取状态变更历史
 * POST /api/orders/{id}/en-route    — 志愿者确认出发
 * POST /api/orders/{id}/arrived     — 志愿者确认到达
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderStatusController {

    private final OrderService orderService;
    private final OrderStatusLogService statusLogService;
    private final RunOrderRepository runOrderRepository;

    public OrderStatusController(OrderService orderService,
                                  OrderStatusLogService statusLogService,
                                  RunOrderRepository runOrderRepository) {
        this.orderService = orderService;
        this.statusLogService = statusLogService;
        this.runOrderRepository = runOrderRepository;
    }

    /** 获取状态变更历史 */
    @GetMapping("/{id}/status-logs")
    public ResponseEntity<List<OrderStatusLogResponse>> getStatusLogs(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 校验权限
        RunOrder order = runOrderRepository.findByIdWithUsers(id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);
        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("您无权查看此订单");
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

    /** 志愿者确认出发 */
    @PostMapping("/{id}/en-route")
    public ResponseEntity<?> driverEnRoute(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderService.driverEnRoute(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }

    /** 志愿者确认到达 */
    @PostMapping("/{id}/arrived")
    public ResponseEntity<?> driverArrived(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        orderService.driverArrived(id, userId);
        return ResponseEntity.ok(Map.of("success", true, "orderId", id));
    }
}

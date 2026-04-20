package com.example.demo.controller;

import com.example.demo.dto.CallInitiateRequest;
import com.example.demo.entity.CallRecord;
import com.example.demo.entity.CallRole;
import com.example.demo.entity.RunOrder;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.PrivateNumberService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 隐私号通话控制器
 */
@RestController
@RequestMapping("/api/orders/{orderId}/call")
public class CallController {

    private final PrivateNumberService privateNumberService;
    private final RunOrderRepository runOrderRepository;

    public CallController(PrivateNumberService privateNumberService,
                          RunOrderRepository runOrderRepository) {
        this.privateNumberService = privateNumberService;
        this.runOrderRepository = runOrderRepository;
    }

    /** 发起通话 */
    @PostMapping("/initiate")
    public ResponseEntity<?> initiateCall(@PathVariable Long orderId,
                                           @Valid @RequestBody CallInitiateRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        RunOrder order = runOrderRepository.findByIdWithUsers(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));

        // 确定双方角色和 ID
        Long blindUserId = order.getBlindUser().getId();
        Long volunteerId = order.getVolunteer() != null ? order.getVolunteer().getId() : null;

        if (volunteerId == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "订单尚未被接单"));
        }

        CallRole callerRole = request.getCallerRole();
        Long calleeId;
        CallRole calleeRole;

        if (callerRole == CallRole.BLIND_USER && userId.equals(blindUserId)) {
            calleeId = volunteerId;
            calleeRole = CallRole.VOLUNTEER;
        } else if (callerRole == CallRole.VOLUNTEER && userId.equals(volunteerId)) {
            calleeId = blindUserId;
            calleeRole = CallRole.BLIND_USER;
        } else {
            throw new OrderPermissionException("您无权发起此通话");
        }

        Map<String, Object> result = privateNumberService.initiateCall(
                orderId, userId, callerRole, calleeId, calleeRole);
        return ResponseEntity.ok(result);
    }

    /** 获取通话记录 */
    @GetMapping("/records")
    public ResponseEntity<List<CallRecord>> getCallRecords(@PathVariable Long orderId) {
        return ResponseEntity.ok(privateNumberService.getCallRecords(orderId));
    }
}

package com.example.demo.controller;

import com.example.demo.dto.EmergencyEventResponse;
import com.example.demo.entity.EmergencyEvent;
import com.example.demo.service.EmergencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import com.example.demo.util.SecurityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客服端控制器
 * 处理客服对紧急事件的操作
 *
 * 所有接口需要客服 JWT 鉴权（csRole claim 存在即为客服）
 */
@RestController
@RequestMapping("/api/cs")
public class CsController {

    private final EmergencyService emergencyService;

    public CsController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    /**
     * 从 SecurityContext 提取客服用户ID，并验证是客服 token
     */
    private Long requireCsUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("未认证");
        }
        // details 中存的是 csRole，如果没有则不是客服 token
        if (auth.getDetails() == null || !(auth.getDetails() instanceof String)) {
            throw new RuntimeException("需要客服权限");
        }
        return (Long) auth.getPrincipal();
    }

    /** 客服获取待处理紧急事件列表 */
    @GetMapping("/emergency-events")
    public ResponseEntity<?> getPendingEvents(
            @RequestParam(required = false) String status) {
        try {
            requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        List<EmergencyEvent> events = emergencyService.getPendingEvents();
        List<EmergencyEventResponse> responses = events.stream()
                .map(EmergencyEventResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** 客服接手事件 */
    @PutMapping("/emergency-events/{eventId}/accept")
    public ResponseEntity<?> acceptEvent(@PathVariable Long eventId) {
        Long csUserId;
        try {
            csUserId = requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        emergencyService.acceptEvent(eventId, csUserId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 通知紧急联系人 */
    @PutMapping("/emergency-events/{eventId}/notify-contact")
    public ResponseEntity<?> notifyContact(@PathVariable Long eventId) {
        Long csUserId;
        try {
            csUserId = requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        emergencyService.notifyContact(eventId, csUserId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 标记已解决 */
    @PutMapping("/emergency-events/{eventId}/resolve")
    public ResponseEntity<?> resolveEvent(@PathVariable Long eventId,
                                           @RequestParam(required = false) String notes) {
        if (notes != null && notes.length() > 1000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "code", 400, "message", "备注不能超过1000个字符"));
        }
        Long csUserId;
        try {
            csUserId = requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        emergencyService.resolveEvent(eventId, csUserId, notes);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 标记误触 */
    @PutMapping("/emergency-events/{eventId}/false-alarm")
    public ResponseEntity<?> markFalseAlarm(@PathVariable Long eventId) {
        Long csUserId;
        try {
            csUserId = requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
        emergencyService.markFalseAlarm(eventId, csUserId);
        return ResponseEntity.ok(Map.of("success", true));
    }

}

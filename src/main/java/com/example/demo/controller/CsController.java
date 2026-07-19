package com.example.demo.controller;

import com.example.demo.dto.EmergencyEventResponse;
import com.example.demo.entity.EmergencyEvent;
import com.example.demo.entity.EmergencyStatus;
import com.example.demo.service.EmergencyService;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /** 客服 csRole（CS / ADMIN），由 requireCsUser() 校验通过后调用 */
    private String getCsRole() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getDetails();
    }

    /** 客服获取紧急事件列表；不传 status 默认待处理列表，传 status 按指定状态筛选（如查看历史） */
    @GetMapping("/emergency-events")
    public ResponseEntity<?> getPendingEvents(
            @RequestParam(required = false) String status) {
        Long csUserId;
        try {
            csUserId = requireCsUser();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }

        List<EmergencyEvent> events;
        if (status != null) {
            EmergencyStatus parsed;
            try {
                parsed = EmergencyStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "无效的状态值: " + status));
            }
            events = emergencyService.getEventsByStatus(parsed);
        } else {
            events = emergencyService.getPendingEvents();
        }

        boolean isAdmin = "ADMIN".equals(getCsRole());
        if (isAdmin && !events.isEmpty()) {
            log.info("客服管理员 {} 查看紧急事件原始GPS坐标，事件数={}", csUserId, events.size());
        }
        List<EmergencyEventResponse> responses = events.stream()
                .map(e -> EmergencyEventResponse.from(e, isAdmin))
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

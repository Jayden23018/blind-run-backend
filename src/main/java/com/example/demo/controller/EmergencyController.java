package com.example.demo.controller;

import com.example.demo.dto.EmergencyTriggerRequest;
import com.example.demo.entity.EmergencyEvent;
import com.example.demo.entity.VolunteerAction;
import com.example.demo.service.EmergencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 紧急事件控制器
 * POST /api/emergency/trigger — 盲人触发紧急事件
 * PUT /api/emergency/{eventId}/volunteer-response — 志愿者响应
 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    private final EmergencyService emergencyService;

    public EmergencyController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    /** 触发紧急事件（盲人端） */
    @PostMapping("/trigger")
    public ResponseEntity<?> triggerEmergency(@RequestBody EmergencyTriggerRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        EmergencyEvent event = emergencyService.triggerEmergency(userId, request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "eventId", event.getId(),
                "status", event.getStatus().name()
        ));
    }

    /** 志愿者响应紧急事件（志愿者端） */
    @PutMapping("/{eventId}/volunteer-response")
    public ResponseEntity<?> volunteerResponse(@PathVariable Long eventId,
                                                @RequestParam VolunteerAction action) {
        Long volunteerId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        emergencyService.handleVolunteerResponse(eventId, volunteerId, action);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "eventId", eventId,
                "action", action.name()
        ));
    }
}

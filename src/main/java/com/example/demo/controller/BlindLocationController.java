package com.example.demo.controller;

import com.example.demo.dto.BlindLocationRequest;
import com.example.demo.service.BlindLocationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 盲人位置上报控制器
 */
@RestController
@RequestMapping("/api/blind")
public class BlindLocationController {

    private final BlindLocationService blindLocationService;

    public BlindLocationController(BlindLocationService blindLocationService) {
        this.blindLocationService = blindLocationService;
    }

    /** 盲人上报实时位置 */
    @PostMapping("/location")
    public ResponseEntity<?> updateLocation(@Valid @RequestBody BlindLocationRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        blindLocationService.updateLocation(userId, request);
        return ResponseEntity.ok(Map.of("success", true));
    }
}

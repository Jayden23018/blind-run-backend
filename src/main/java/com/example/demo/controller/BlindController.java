package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.BlindProfileResponse;
import com.example.demo.dto.BlindProfileUpdateRequest;
import com.example.demo.dto.BlindVerifyRequest;
import com.example.demo.service.BlindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 盲人用户控制器
 */
@RestController
@RequestMapping("/api/blind")
@Tag(name = "盲人用户", description = "盲人用户资料和身份认证接口")
public class BlindController {

    private final BlindService blindService;

    public BlindController(BlindService blindService) {
        this.blindService = blindService;
    }

    @GetMapping("/profile")
    @Operation(summary = "获取盲人资料")
    public ResponseEntity<BlindProfileResponse> getProfile() {
        Long userId = getCurrentUserId();
        BlindProfileResponse profile = blindService.getProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    @Operation(summary = "更新盲人资料")
    public ResponseEntity<?> updateProfile(@Valid @RequestBody BlindProfileUpdateRequest request) {
        Long userId = getCurrentUserId();
        BlindProfileResponse profile = blindService.updateProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    @PostMapping("/verify-identity")
    @Operation(summary = "身份认证", description = "提交身份证姓名和号码进行二要素核验")
    public ResponseEntity<?> verifyIdentity(@Valid @RequestBody BlindVerifyRequest request) {
        Long userId = getCurrentUserId();
        BlindProfileResponse result = blindService.verifyIdentity(
                userId, request.getIdCardName(), request.getIdCardNumber());

        if (result.getVerifyStatus().name().equals("VERIFIED")) {
            return ResponseEntity.ok(ApiResponse.ok("身份认证通过"));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, "身份信息与公安库不匹配，请核实后重试"));
        }
    }

    private Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

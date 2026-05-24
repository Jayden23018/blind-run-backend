package com.example.demo.controller;

import com.example.demo.dto.DispatchStatusRequest;
import com.example.demo.dto.VolunteerProfileResponse;
import com.example.demo.dto.VolunteerProfileUpdateRequest;
import com.example.demo.dto.VolunteerLocationRequest;
import com.example.demo.service.VolunteerLocationService;
import com.example.demo.service.VolunteerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.demo.util.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 志愿者控制器
 *
 * GET    /api/volunteer/profile                → 获取志愿者资料
 * PUT    /api/volunteer/profile                → 更新志愿者资料
 * POST   /api/volunteer/verification           → 上传资质证件
 * GET    /api/volunteer/verification/status     → 获取认证状态
 * POST   /api/volunteer/location               → 上报实时位置
 */
@RestController
@RequestMapping("/api/volunteer")
public class VolunteerController {

    private final VolunteerLocationService volunteerLocationService;
    private final VolunteerService volunteerService;

    public VolunteerController(VolunteerLocationService volunteerLocationService,
                               VolunteerService volunteerService) {
        this.volunteerLocationService = volunteerLocationService;
        this.volunteerService = volunteerService;
    }

    @GetMapping("/profile")
    public ResponseEntity<VolunteerProfileResponse> getProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        VolunteerProfileResponse profile = volunteerService.getProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@Valid @RequestBody VolunteerProfileUpdateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        VolunteerProfileResponse profile = volunteerService.updateProfile(userId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", profile));
    }

    @PostMapping(value = "/verification", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitVerification(
            @Parameter(description = "资质证件文件（图片或PDF）", required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
            @RequestParam("file") MultipartFile file) {
        Long userId = SecurityUtils.getCurrentUserId();

        // 文件校验
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "code", 400, "message", "资质证件文件不能为空"));
        }
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "code", 400, "message", "文件格式仅支持图片或PDF"));
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "code", 400, "message", "文件大小不能超过5MB"));
        }

        String status = volunteerService.submitVerification(userId, file);
        return ResponseEntity.ok(Map.of("success", true, "status", status));
    }

    @GetMapping("/verification/status")
    public ResponseEntity<?> getVerificationStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        String status = volunteerService.getVerificationStatus(userId);
        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * 切换接单开关：志愿者可随时暂停/恢复接单，不影响在线位置上报
     * PUT /api/volunteer/dispatch-status
     */
    @PutMapping("/dispatch-status")
    public ResponseEntity<?> updateDispatchStatus(@Valid @RequestBody DispatchStatusRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        volunteerLocationService.updateDispatchStatus(userId, request.wantsDispatch());
        return ResponseEntity.ok(Map.of("success", true, "wantsDispatch", request.wantsDispatch()));
    }

    /**
     * @deprecated 已由 WebSocket 消息替代，前端应通过 WebSocket 发送 { type: "LOCATION_UPDATE", lat, lng }
     */
    @Deprecated
    @PostMapping("/location")
    public ResponseEntity<?> updateLocation(@Valid @RequestBody VolunteerLocationRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        volunteerLocationService.updateLocation(
                userId,
                request.getLatitude(),
                request.getLongitude(),
                request.getIsOnline()
        );
        return ResponseEntity.ok(Map.of("success", true));
    }
}

package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.volunteer.*;
import com.example.demo.service.TrainingService;
import com.example.demo.service.VolunteerRegistrationService;
import com.example.demo.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 志愿者注册控制器。
 *
 * 流程（动作活体改造后）：step1（基本信息+身份证）→ step3（动作活体 init/result）→ 培训。
 * step2（身份证照片上传）已下线。
 */
@Slf4j
@RestController
@RequestMapping("/api/volunteer/registration")
@Tag(name = "志愿者注册", description = "志愿者注册流程接口")
public class VolunteerRegistrationController {

    private final VolunteerRegistrationService registrationService;
    private final TrainingService trainingService;

    public VolunteerRegistrationController(VolunteerRegistrationService registrationService,
                                           TrainingService trainingService) {
        this.registrationService = registrationService;
        this.trainingService = trainingService;
    }

    /**
     * 获取注册状态
     */
    @GetMapping("/status")
    @Operation(summary = "获取注册状态")
    public ResponseEntity<?> getRegistrationStatus() {
        Long userId = getCurrentUserId();
        RegistrationStatusResponse response = registrationService.getRegistrationStatus(userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 提交基本信息（含身份证姓名+号码，提交时自动二要素核验）
     */
    @PostMapping("/step1")
    @Operation(summary = "提交基本信息（Step 1，含身份证信息）")
    public ResponseEntity<?> submitBasicInfo(@Valid @RequestBody BasicInfoRequest request) {
        Long userId = getCurrentUserId();
        registrationService.submitBasicInfo(userId, request);
        return ResponseEntity.ok(ApiResponse.ok("基本信息已提交"));
    }

    /**
     * 发起动作活体认证（step3 第一段）。
     * 返回 certifyId + CertifyUrl，前端打开 CertifyUrl 完成动作活体后轮询 result。
     */
    @PostMapping("/step3/face-verify/init")
    @Operation(summary = "发起动作活体认证（Step 3 - init）")
    public ResponseEntity<?> initFaceVerify(@Valid @RequestBody FaceVerifyInitRequest request) {
        Long userId = getCurrentUserId();
        FaceVerifyInitResponse response = registrationService.initFaceVerify(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 查询动作活体认证结果（step3 第二段，前端轮询）。
     */
    @PostMapping("/step3/face-verify/result")
    @Operation(summary = "查询动作活体认证结果（Step 3 - result）")
    public ResponseEntity<?> queryFaceVerifyResult(@Valid @RequestBody FaceVerifyResultRequest request) {
        Long userId = getCurrentUserId();
        FaceVerifyResultResponse response = registrationService.queryFaceVerifyResult(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 获取培训课程列表
     */
    @GetMapping("/training/courses")
    public ResponseEntity<?> getTrainingCourses() {
        Long userId = getCurrentUserId();
        var courses = trainingService.getCourses(userId);
        return ResponseEntity.ok(ApiResponse.ok(courses));
    }

    /**
     * 提交学习进度
     */
    @PostMapping("/training/progress")
    public ResponseEntity<?> submitTrainingProgress(@Valid @RequestBody TrainingProgressRequest request) {
        Long userId = getCurrentUserId();
        trainingService.submitProgress(userId, request);
        trainingService.checkAllCoursesCompleted(userId);
        return ResponseEntity.ok(ApiResponse.ok("进度已更新"));
    }

    /**
     * 获取课程测验题目
     */
    @GetMapping("/training/quiz/{courseId}")
    public ResponseEntity<?> getQuizQuestions(@PathVariable Long courseId) {
        Long userId = getCurrentUserId();
        var questions = trainingService.getQuizQuestions(userId, courseId);
        return ResponseEntity.ok(ApiResponse.ok(questions));
    }

    /**
     * 提交测验答案
     */
    @PostMapping("/training/quiz/answer")
    public ResponseEntity<?> submitQuizAnswer(@Valid @RequestBody QuizAnswerRequest request) {
        Long userId = getCurrentUserId();
        QuizResultResponse result = trainingService.submitQuizAnswer(userId, request);
        trainingService.checkAllCoursesCompleted(userId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // === 私有方法 ===

    private Long getCurrentUserId() {
        return SecurityUtils.getCurrentUserId();
    }
}

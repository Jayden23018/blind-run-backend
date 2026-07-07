package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.volunteer.*;
import com.example.demo.service.TrainingService;
import com.example.demo.service.VolunteerRegistrationService;
import com.example.demo.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 志愿者注册控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/volunteer/registration")
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
    public ResponseEntity<?> getRegistrationStatus() {
        Long userId = getCurrentUserId();
        RegistrationStatusResponse response = registrationService.getRegistrationStatus(userId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 提交基本信息
     */
    @PostMapping("/step1")
    public ResponseEntity<?> submitBasicInfo(@Valid @RequestBody BasicInfoRequest request) {
        Long userId = getCurrentUserId();
        registrationService.submitBasicInfo(userId, request);
        return ResponseEntity.ok(ApiResponse.ok("基本信息已提交"));
    }

    /**
     * 上传身份证
     */
    @Operation(summary = "上传身份证（Step 2）", description = "multipart/form-data 格式，上传身份证正反面照片")
    @PostMapping(value = "/step2/id-card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadIdCard(
            @Parameter(description = "身份证姓名", required = true, example = "张三")
            @RequestParam("idCardName") String idCardName,
            @Parameter(description = "身份证号码", required = true, example = "440305200001011234")
            @RequestParam("idCardNumber") String idCardNumber,
            @Parameter(description = "身份证正面照片（人像面）", required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
            @RequestParam("frontFile") MultipartFile frontFile,
            @Parameter(description = "身份证背面照片（国徽面）", required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
            @RequestParam("backFile") MultipartFile backFile) {
        Long userId = getCurrentUserId();

        // 参数校验
        if (idCardName == null || idCardName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证姓名不能为空"));
        }
        if (idCardName.length() > 50) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证姓名不能超过50个字符"));
        }
        if (idCardNumber == null || idCardNumber.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证号码不能为空"));
        }
        if (!idCardNumber.matches("^\\d{17}[\\dXx]$")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证号码格式不正确"));
        }
        if (frontFile == null || frontFile.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证正面照片不能为空"));
        }
        if (backFile == null || backFile.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "身份证背面照片不能为空"));
        }
        String frontContentType = frontFile.getContentType();
        if (frontContentType == null || !frontContentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "正面照片必须为图片格式"));
        }
        String backContentType = backFile.getContentType();
        if (backContentType == null || !backContentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "背面照片必须为图片格式"));
        }
        long maxSize = 5 * 1024 * 1024;
        if (frontFile.getSize() > maxSize) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "正面照片不能超过5MB"));
        }
        if (backFile.getSize() > maxSize) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "背面照片不能超过5MB"));
        }

        registrationService.uploadIdCard(userId, idCardName, idCardNumber, frontFile, backFile);
        return ResponseEntity.ok(ApiResponse.ok("身份证已上传，请继续完成人脸核验"));
    }

    /**
     * 人脸验证（上传自拍照片，调用阿里云API比对）
     */
    @Operation(summary = "人脸验证（Step 3）", description = "上传自拍照片进行人脸比对")
    @PostMapping(value = "/step3/face-verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> faceVerify(
            @Parameter(description = "人脸自拍照片", required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
            @RequestParam("facePhoto") MultipartFile facePhoto) {
        Long userId = getCurrentUserId();

        // 参数校验
        if (facePhoto == null || facePhoto.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "人脸照片不能为空"));
        }
        String contentType = facePhoto.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "照片必须为图片格式"));
        }
        long maxSize = 5 * 1024 * 1024;
        if (facePhoto.getSize() > maxSize) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "照片不能超过5MB"));
        }

        FaceVerifyInitResponse response = registrationService.initFaceVerify(userId, facePhoto);
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

        // 检查是否完成所有课程
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

        // 检查是否完成所有课程
        trainingService.checkAllCoursesCompleted(userId);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // === 私有方法 ===

    private Long getCurrentUserId() {
        return SecurityUtils.getCurrentUserId();
    }
}

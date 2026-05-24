package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.admin.CertReviewItemResponse;
import com.example.demo.dto.admin.CertReviewRequest;
import com.example.demo.dto.admin.IdReviewRequest;
import com.example.demo.dto.admin.QuizQuestionRequest;
import com.example.demo.dto.admin.TrainingCourseRequest;
import com.example.demo.dto.admin.TrainingCourseResponse;
import com.example.demo.dto.admin.TrainingQuizQuestionResponse;
import com.example.demo.dto.admin.TrainingStatsResponse;
import com.example.demo.dto.admin.VolunteerReviewItemResponse;
import com.example.demo.service.AdminVolunteerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员志愿者管理控制器
 * 鉴权由 SecurityConfig 统一负责（/api/admin/** → CS_ADMIN）
 */
@RestController
@RequestMapping("/api/admin/volunteers")
@Validated
public class AdminVolunteerController {

    private final AdminVolunteerService adminVolunteerService;

    public AdminVolunteerController(AdminVolunteerService adminVolunteerService) {
        this.adminVolunteerService = adminVolunteerService;
    }

    // ===== 资质证书审核 =====

    @GetMapping("/review/cert")
    public ResponseEntity<ApiResponse<List<CertReviewItemResponse>>> getVolunteersForCertReview() {
        return ResponseEntity.ok(ApiResponse.ok(adminVolunteerService.getVolunteersForCertReview()));
    }

    @PostMapping("/review/cert")
    public ResponseEntity<ApiResponse<String>> reviewCertificate(@Valid @RequestBody CertReviewRequest request) {
        adminVolunteerService.reviewCertificate(request);
        return ResponseEntity.ok(ApiResponse.ok("审核完成"));
    }

    // ===== 身份证审核 =====

    @GetMapping("/review/id")
    public ResponseEntity<ApiResponse<List<VolunteerReviewItemResponse>>> getVolunteersForIdReview() {
        return ResponseEntity.ok(ApiResponse.ok(adminVolunteerService.getVolunteersForIdReview()));
    }

    @PostMapping("/review/id")
    public ResponseEntity<ApiResponse<String>> reviewIdCard(@Valid @RequestBody IdReviewRequest request) {
        adminVolunteerService.reviewIdCard(request);
        return ResponseEntity.ok(ApiResponse.ok("审核完成"));
    }

    @GetMapping("/training/stats")
    public ResponseEntity<ApiResponse<TrainingStatsResponse>> getTrainingStats() {
        return ResponseEntity.ok(ApiResponse.ok(adminVolunteerService.getTrainingStats()));
    }

    @PostMapping("/training/courses")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> createCourse(
            @Valid @RequestBody TrainingCourseRequest request) {
        TrainingCourseResponse response = adminVolunteerService.createCourse(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/training/courses/{id}")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> updateCourse(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody TrainingCourseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(adminVolunteerService.updateCourse(id, request)));
    }

    @DeleteMapping("/training/courses/{id}")
    public ResponseEntity<ApiResponse<String>> deleteCourse(@PathVariable @Min(1) Long id) {
        adminVolunteerService.deleteCourse(id);
        return ResponseEntity.ok(ApiResponse.ok("课程已删除"));
    }

    @PostMapping("/training/courses/{courseId}/questions")
    public ResponseEntity<ApiResponse<TrainingQuizQuestionResponse>> createQuestion(
            @PathVariable Long courseId,
            @Valid @RequestBody QuizQuestionRequest request) {
        TrainingQuizQuestionResponse response = adminVolunteerService.createQuestion(courseId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/training/questions/{id}")
    public ResponseEntity<ApiResponse<TrainingQuizQuestionResponse>> updateQuestion(
            @PathVariable @Min(1) Long id,
            @Valid @RequestBody QuizQuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(adminVolunteerService.updateQuestion(id, request)));
    }

    @DeleteMapping("/training/questions/{id}")
    public ResponseEntity<ApiResponse<String>> deleteQuestion(@PathVariable @Min(1) Long id) {
        adminVolunteerService.deleteQuestion(id);
        return ResponseEntity.ok(ApiResponse.ok("题目已删除"));
    }
}

package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.admin.IdReviewRequest;
import com.example.demo.dto.admin.QuizQuestionRequest;
import com.example.demo.dto.admin.TrainingCourseRequest;
import com.example.demo.dto.admin.TrainingStatsResponse;
import com.example.demo.dto.admin.VolunteerReviewItemResponse;
import com.example.demo.entity.TrainingCourse;
import com.example.demo.entity.TrainingQuizQuestion;
import com.example.demo.repository.TrainingCourseRepository;
import com.example.demo.repository.TrainingQuizQuestionRepository;
import com.example.demo.service.AdminVolunteerService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员志愿者管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/volunteers")
public class AdminVolunteerController {

    private final AdminVolunteerService adminVolunteerService;
    private final TrainingCourseRepository trainingCourseRepository;
    private final TrainingQuizQuestionRepository questionRepository;

    public AdminVolunteerController(AdminVolunteerService adminVolunteerService,
                                     TrainingCourseRepository trainingCourseRepository,
                                     TrainingQuizQuestionRepository questionRepository) {
        this.adminVolunteerService = adminVolunteerService;
        this.trainingCourseRepository = trainingCourseRepository;
        this.questionRepository = questionRepository;
    }

    /**
     * 获取待审核身份证列表
     */
    @GetMapping("/review/id")
    public ResponseEntity<?> getVolunteersForIdReview() {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        List<VolunteerReviewItemResponse> list = adminVolunteerService.getVolunteersForIdReview();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    /**
     * 审核身份证
     */
    @PostMapping("/review/id")
    public ResponseEntity<?> reviewIdCard(@Valid @RequestBody IdReviewRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        adminVolunteerService.reviewIdCard(request);
        return ResponseEntity.ok(ApiResponse.ok("审核完成"));
    }

    /**
     * 获取培训统计数据
     */
    @GetMapping("/training/stats")
    public ResponseEntity<?> getTrainingStats() {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        TrainingStatsResponse stats = adminVolunteerService.getTrainingStats();
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    /**
     * 创建培训课程
     */
    @PostMapping("/training/courses")
    public ResponseEntity<?> createCourse(@Valid @RequestBody TrainingCourseRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        TrainingCourse course = new TrainingCourse();
        course.setTitle(request.getTitle());
        course.setDescription(request.getDescription());
        course.setDurationMinutes(request.getDurationMinutes());
        course.setVideoUrl(request.getVideoUrl());
        course.setContent(request.getContent());
        course.setDisplayOrder(request.getDisplayOrder());
        course.setIsActive(request.getIsActive());

        trainingCourseRepository.save(course);
        return ResponseEntity.ok(ApiResponse.ok(course));
    }

    /**
     * 更新培训课程
     */
    @PutMapping("/training/courses/{id}")
    public ResponseEntity<?> updateCourse(@PathVariable Long id,
                                         @Valid @RequestBody TrainingCourseRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        TrainingCourse course = trainingCourseRepository.findById(id).orElse(null);
        if (course == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "课程不存在"));
        }

        course.setTitle(request.getTitle());
        course.setDescription(request.getDescription());
        course.setDurationMinutes(request.getDurationMinutes());
        course.setVideoUrl(request.getVideoUrl());
        course.setContent(request.getContent());
        course.setDisplayOrder(request.getDisplayOrder());
        course.setIsActive(request.getIsActive());

        trainingCourseRepository.save(course);
        return ResponseEntity.ok(ApiResponse.ok(course));
    }

    /**
     * 删除培训课程
     */
    @DeleteMapping("/training/courses/{id}")
    public ResponseEntity<?> deleteCourse(@PathVariable Long id) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        if (!trainingCourseRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "课程不存在"));
        }

        trainingCourseRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("课程已删除"));
    }

    // === 题目管理 ===

    /**
     * 创建题目
     */
    @PostMapping("/training/courses/{courseId}/questions")
    public ResponseEntity<?> createQuestion(@PathVariable Long courseId,
                                            @Valid @RequestBody QuizQuestionRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        if (!trainingCourseRepository.existsById(courseId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "课程不存在"));
        }

        TrainingQuizQuestion question = new TrainingQuizQuestion();
        question.setCourseId(courseId);
        question.setQuestionText(request.getQuestionText());
        question.setQuestionType(com.example.demo.entity.QuestionType.valueOf(request.getQuestionType()));
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            question.setOptions(mapper.writeValueAsString(request.getOptions()));
            question.setCorrectAnswer(mapper.writeValueAsString(request.getCorrectAnswer()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "JSON序列化失败"));
        }
        question.setExplanation(request.getExplanation());
        question.setDisplayOrder(request.getDisplayOrder());

        questionRepository.save(question);
        return ResponseEntity.ok(ApiResponse.ok(question));
    }

    /**
     * 更新题目
     */
    @PutMapping("/training/questions/{id}")
    public ResponseEntity<?> updateQuestion(@PathVariable Long id,
                                            @Valid @RequestBody QuizQuestionRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        TrainingQuizQuestion question = questionRepository.findById(id).orElse(null);
        if (question == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "题目不存在"));
        }

        question.setQuestionText(request.getQuestionText());
        question.setQuestionType(com.example.demo.entity.QuestionType.valueOf(request.getQuestionType()));
        try {
            question.setOptions(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getOptions()));
            question.setCorrectAnswer(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getCorrectAnswer()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "JSON序列化失败"));
        }
        question.setExplanation(request.getExplanation());
        question.setDisplayOrder(request.getDisplayOrder());

        questionRepository.save(question);
        return ResponseEntity.ok(ApiResponse.ok(question));
    }

    /**
     * 删除题目
     */
    @DeleteMapping("/training/questions/{id}")
    public ResponseEntity<?> deleteQuestion(@PathVariable Long id) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        if (!questionRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "题目不存在"));
        }

        questionRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("题目已删除"));
    }

    // === 私有方法 ===

    private void requireAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("未认证");
        }
        if (auth.getDetails() == null || !(auth.getDetails() instanceof String)) {
            throw new RuntimeException("需要管理员权限");
        }
        Long csUserId = (Long) auth.getPrincipal();
        // 简化实现：直接从 details 获取 csRole
        String csRole = (String) auth.getDetails();
        if (!"ADMIN".equals(csRole)) {
            throw new RuntimeException("需要管理员权限，当前角色：" + csRole);
        }
    }
}

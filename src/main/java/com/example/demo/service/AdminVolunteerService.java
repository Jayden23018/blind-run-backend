package com.example.demo.service;

import com.example.demo.dto.admin.CertReviewItemResponse;
import com.example.demo.dto.admin.CertReviewRequest;
import com.example.demo.dto.admin.IdReviewRequest;
import com.example.demo.dto.admin.QuizQuestionRequest;
import com.example.demo.dto.admin.TrainingCourseRequest;
import com.example.demo.dto.admin.TrainingCourseResponse;
import com.example.demo.dto.admin.TrainingQuizQuestionResponse;
import com.example.demo.dto.admin.TrainingStatsResponse;
import com.example.demo.dto.admin.VolunteerReviewItemResponse;
import com.example.demo.entity.*;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.TrainingCourseRepository;
import com.example.demo.repository.TrainingProgressRepository;
import com.example.demo.repository.TrainingQuizQuestionRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.util.PhoneMaskUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员志愿者审核服务
 */
@Slf4j
@Service
public class AdminVolunteerService {

    private final VolunteerProfileRepository volunteerProfileRepository;
    private final TrainingProgressRepository trainingProgressRepository;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final TrainingCourseRepository trainingCourseRepository;
    private final TrainingQuizQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;
    private final DispatchService dispatchService;

    public AdminVolunteerService(VolunteerProfileRepository volunteerProfileRepository,
                                  TrainingProgressRepository trainingProgressRepository,
                                  NotificationService notificationService,
                                  FileStorageService fileStorageService,
                                  TrainingCourseRepository trainingCourseRepository,
                                  TrainingQuizQuestionRepository questionRepository,
                                  ObjectMapper objectMapper,
                                  DispatchService dispatchService) {
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.trainingProgressRepository = trainingProgressRepository;
        this.notificationService = notificationService;
        this.fileStorageService = fileStorageService;
        this.trainingCourseRepository = trainingCourseRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
        this.dispatchService = dispatchService;
    }

    /**
     * 获取待审核身份证列表
     */
    public List<VolunteerReviewItemResponse> getVolunteersForIdReview() {
        return volunteerProfileRepository.findByIdVerifyStatus(IdVerifyStatus.PENDING).stream()
                .map(this::toReviewItemResponse)
                .toList();
    }

    /**
     * 审核身份证
     */
    @Transactional
    public void reviewIdCard(IdReviewRequest request) {
        var profile = volunteerProfileRepository.findByUserId(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("志愿者不存在"));

        if (profile.getIdVerifyStatus() != IdVerifyStatus.PENDING) {
            throw new IllegalArgumentException("该志愿者不待审核");
        }

        if (request.getApproved()) {
            // 审核通过
            profile.setIdVerifyStatus(IdVerifyStatus.APPROVED);
            profile.setIdVerifyRejectionReason(null);

            // 发送通过通知
            Map<String, String> params = new HashMap<>();
            notificationService.sendNotification(request.getUserId(), "ID_VERIFY_APPROVED",
                    TargetRole.VOLUNTEER, params);

            log.info("管理员审核通过志愿者 {} 的身份证", request.getUserId());
        } else {
            // 审核拒绝
            profile.setIdVerifyStatus(IdVerifyStatus.REJECTED);
            profile.setIdVerifyRejectionReason(request.getRejectionReason());
            profile.setRegistrationStep(RegistrationStep.STEP_2_ID_UPLOAD); // 回退到 STEP_2

            // 发送拒绝通知
            Map<String, String> params = Map.of("reason",
                    request.getRejectionReason() != null ? request.getRejectionReason() : "未填写原因");
            notificationService.sendNotification(request.getUserId(), "ID_VERIFY_REJECTED",
                    TargetRole.VOLUNTEER, params);

            log.info("管理员审核拒绝志愿者 {} 的身份证，原因：{}", request.getUserId(), request.getRejectionReason());
        }

        volunteerProfileRepository.save(profile);
        dispatchService.evictProfileCache(request.getUserId());
    }

    /**
     * 获取待审核资质证书列表
     */
    public List<CertReviewItemResponse> getVolunteersForCertReview() {
        return volunteerProfileRepository.findByVerificationStatus(VerificationStatus.PENDING).stream()
                .map(this::toCertReviewItemResponse)
                .toList();
    }

    /**
     * 审核资质证书
     */
    @Transactional
    public void reviewCertificate(CertReviewRequest request) {
        var profile = volunteerProfileRepository.findByUserId(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("志愿者不存在"));

        if (profile.getVerificationStatus() != VerificationStatus.PENDING) {
            throw new IllegalArgumentException("该志愿者证书不在待审核状态");
        }

        if (request.getApproved()) {
            // 审核通过
            profile.setVerificationStatus(VerificationStatus.APPROVED);
            profile.setVerified(true);

            Map<String, String> params = new HashMap<>();
            notificationService.sendNotification(request.getUserId(), "CERT_APPROVED",
                    TargetRole.VOLUNTEER, params);

            log.info("管理员审核通过志愿者 {} 的资质证书", request.getUserId());
        } else {
            // 审核拒绝
            profile.setVerificationStatus(VerificationStatus.REJECTED);
            profile.setVerified(false);

            Map<String, String> params = Map.of("reason",
                    request.getRejectionReason() != null ? request.getRejectionReason() : "未填写原因");
            notificationService.sendNotification(request.getUserId(), "CERT_REJECTED",
                    TargetRole.VOLUNTEER, params);

            log.info("管理员审核拒绝志愿者 {} 的资质证书，原因：{}", request.getUserId(), request.getRejectionReason());
        }

        volunteerProfileRepository.save(profile);
        dispatchService.evictProfileCache(request.getUserId());
    }

    /**
     * 获取培训统计数据
     */
    public TrainingStatsResponse getTrainingStats() {
        long totalVolunteers = volunteerProfileRepository.count();
        long completedVolunteers = volunteerProfileRepository.countByRegistrationStep(RegistrationStep.STEP_4_COMPLETED);
        long inProgressVolunteers = trainingProgressRepository.countDistinctVolunteerIdByStatus(TrainingProgressStatus.IN_PROGRESS);
        double completionRate = totalVolunteers > 0 ? (completedVolunteers * 100.0 / totalVolunteers) : 0.0;

        return new TrainingStatsResponse(
                totalVolunteers,
                completedVolunteers,
                inProgressVolunteers,
                Math.round(completionRate * 10.0) / 10.0
        );
    }

    // === 培训课程管理 ===

    @Transactional
    public TrainingCourseResponse createCourse(TrainingCourseRequest request) {
        TrainingCourse course = new TrainingCourse();
        applyCourseFields(course, request);
        return TrainingCourseResponse.from(trainingCourseRepository.save(course));
    }

    @Transactional
    public TrainingCourseResponse updateCourse(Long id, TrainingCourseRequest request) {
        TrainingCourse course = trainingCourseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("课程不存在: " + id));
        applyCourseFields(course, request);
        return TrainingCourseResponse.from(trainingCourseRepository.save(course));
    }

    @Transactional
    public void deleteCourse(Long id) {
        if (!trainingCourseRepository.existsById(id)) {
            throw new ResourceNotFoundException("课程不存在: " + id);
        }
        trainingCourseRepository.deleteById(id);
    }

    // === 测验题目管理 ===

    @Transactional
    public TrainingQuizQuestionResponse createQuestion(Long courseId, QuizQuestionRequest request) {
        if (!trainingCourseRepository.existsById(courseId)) {
            throw new ResourceNotFoundException("课程不存在: " + courseId);
        }
        TrainingQuizQuestion question = new TrainingQuizQuestion();
        question.setCourseId(courseId);
        applyQuestionFields(question, request);
        questionRepository.save(question);
        return toQuestionResponse(question, request.getOptions(), request.getCorrectAnswer());
    }

    @Transactional
    public TrainingQuizQuestionResponse updateQuestion(Long id, QuizQuestionRequest request) {
        TrainingQuizQuestion question = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("题目不存在: " + id));
        applyQuestionFields(question, request);
        questionRepository.save(question);
        return toQuestionResponse(question, request.getOptions(), request.getCorrectAnswer());
    }

    @Transactional
    public void deleteQuestion(Long id) {
        if (!questionRepository.existsById(id)) {
            throw new ResourceNotFoundException("题目不存在: " + id);
        }
        questionRepository.deleteById(id);
    }

    // === 私有方法 ===

    private void applyCourseFields(TrainingCourse course, TrainingCourseRequest request) {
        course.setTitle(request.getTitle());
        course.setDescription(request.getDescription());
        course.setDurationMinutes(request.getDurationMinutes());
        course.setVideoUrl(request.getVideoUrl());
        course.setContent(request.getContent());
        course.setDisplayOrder(request.getDisplayOrder());
        course.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
    }

    private void applyQuestionFields(TrainingQuizQuestion question, QuizQuestionRequest request) {
        question.setQuestionText(request.getQuestionText());
        question.setQuestionType(QuestionType.valueOf(request.getQuestionType()));
        try {
            question.setOptions(objectMapper.writeValueAsString(request.getOptions()));
            question.setCorrectAnswer(objectMapper.writeValueAsString(request.getCorrectAnswer()));
        } catch (Exception e) {
            throw new IllegalArgumentException("选项序列化失败: " + e.getMessage());
        }
        question.setExplanation(request.getExplanation());
        question.setDisplayOrder(request.getDisplayOrder());
    }

    private TrainingQuizQuestionResponse toQuestionResponse(TrainingQuizQuestion question,
                                                              List<String> options,
                                                              List<String> correctAnswer) {
        return TrainingQuizQuestionResponse.from(question, options, correctAnswer);
    }

    private VolunteerReviewItemResponse toReviewItemResponse(com.example.demo.entity.VolunteerProfile profile) {
        return new VolunteerReviewItemResponse(
                profile.getUserId(),
                profile.getName(),
                PhoneMaskUtils.mask(profile.getPhone()),
                maskIdCard(profile.getIdCardNumber()),
                profile.getIdCardName(),
                fileStorageService.getUrl(profile.getIdCardFrontUrl()),
                fileStorageService.getUrl(profile.getIdCardBackUrl()),
                fileStorageService.getUrl(profile.getFacePhotoUrl()),
                profile.getRegistrationStep(),
                profile.getIdVerifyStatus(),
                profile.getFaceVerifyStatus(),
                profile.getUpdatedAt()
        );
    }

    private CertReviewItemResponse toCertReviewItemResponse(VolunteerProfile profile) {
        return new CertReviewItemResponse(
                profile.getUserId(),
                profile.getName(),
                PhoneMaskUtils.mask(profile.getPhone()),
                fileStorageService.getUrl(profile.getVerificationDocUrl()),
                profile.getVerificationStatus(),
                profile.getRegistrationStep(),
                profile.getUpdatedAt()
        );
    }

    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 18) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(14);
    }
}

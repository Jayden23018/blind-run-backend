package com.example.demo.service;

import com.example.demo.dto.admin.IdReviewRequest;
import com.example.demo.util.PhoneMaskUtils;
import com.example.demo.dto.admin.TrainingStatsResponse;
import com.example.demo.dto.admin.VolunteerReviewItemResponse;
import com.example.demo.entity.IdVerifyStatus;
import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.TargetRole;
import com.example.demo.entity.TrainingProgress;
import com.example.demo.entity.TrainingProgressStatus;
import com.example.demo.repository.TrainingProgressRepository;
import com.example.demo.repository.VolunteerProfileRepository;
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

    public AdminVolunteerService(VolunteerProfileRepository volunteerProfileRepository,
                                  TrainingProgressRepository trainingProgressRepository,
                                  NotificationService notificationService) {
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.trainingProgressRepository = trainingProgressRepository;
        this.notificationService = notificationService;
    }

    /**
     * 获取待审核身份证列表
     */
    public List<VolunteerReviewItemResponse> getVolunteersForIdReview() {
        return volunteerProfileRepository.findAll().stream()
                .filter(p -> p.getIdVerifyStatus() == IdVerifyStatus.PENDING)
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
    }

    /**
     * 获取培训统计数据
     */
    public TrainingStatsResponse getTrainingStats() {
        long totalVolunteers = volunteerProfileRepository.count();
        long completedVolunteers = volunteerProfileRepository.findAll().stream()
                .filter(p -> p.getRegistrationStep() == RegistrationStep.STEP_4_COMPLETED)
                .count();

        long inProgressVolunteers = trainingProgressRepository.findAll().stream()
                .filter(p -> p.getStatus() == TrainingProgressStatus.IN_PROGRESS)
                .map(TrainingProgress::getVolunteerId)
                .distinct()
                .count();

        double completionRate = totalVolunteers > 0 ? (completedVolunteers * 100.0 / totalVolunteers) : 0.0;

        return new TrainingStatsResponse(
                totalVolunteers,
                completedVolunteers,
                inProgressVolunteers,
                Math.round(completionRate * 10.0) / 10.0
        );
    }

    // === 私有方法 ===

    private VolunteerReviewItemResponse toReviewItemResponse(com.example.demo.entity.VolunteerProfile profile) {
        return new VolunteerReviewItemResponse(
                profile.getUserId(),
                profile.getName(),
                PhoneMaskUtils.mask(profile.getPhone()),
                maskIdCard(profile.getIdCardNumber()),
                profile.getIdCardName(),
                profile.getIdCardFrontUrl(),
                profile.getIdCardBackUrl(),
                profile.getFacePhotoUrl(),
                profile.getRegistrationStep(),
                profile.getIdVerifyStatus(),
                profile.getFaceVerifyStatus(),
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

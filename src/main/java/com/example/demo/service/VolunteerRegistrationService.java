package com.example.demo.service;

import com.example.demo.dto.volunteer.*;
import com.example.demo.entity.*;
import com.example.demo.exception.RegistrationStepException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.service.FaceVerifyService.FaceVerifyInitResult;
import com.example.demo.service.FaceVerifyService.FaceVerifyResult;
import com.example.demo.service.impl.AliyunIdVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 志愿者注册流程服务。
 *
 * 流程（动作活体改造后）：
 *   step1 BASIC_INFO（含身份证姓名+号码，提交时自动 Id2Meta 二要素核验）
 *     → step3 FACE_VERIFY（动作活体：init → 前端打开 CertifyUrl → result 轮询）
 *     → step4 TRAINING → COMPLETED
 *   step2（身份证照片上传）已下线；历史数据卡在 STEP_2 的用户在 getRegistrationStatus 自动迁移到 STEP_3。
 */
@Slf4j
@Service
public class VolunteerRegistrationService {

    private final VolunteerProfileRepository volunteerProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AliyunIdVerifyService idVerifyService;        // Id2Meta 二要素核验（step1 + 盲人共用）
    private final FaceVerifyService faceVerifyService;          // 动作活体（aliyun/test 自动切换）

    @Value("${app.face-verify.return-url:}")
    private String faceVerifyReturnUrl;

    public VolunteerRegistrationService(VolunteerProfileRepository volunteerProfileRepository,
                                        UserRepository userRepository,
                                        NotificationService notificationService,
                                        AliyunIdVerifyService idVerifyService,
                                        FaceVerifyService faceVerifyService) {
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.idVerifyService = idVerifyService;
        this.faceVerifyService = faceVerifyService;
    }

    /**
     * 提交基本信息（STEP_1 → STEP_3）。
     * 含身份证姓名+号码，提交时自动调用 Id2Meta 二要素核验：
     *   通过 → idVerifyStatus=APPROVED，直接进入 step3
     *   失败 → idVerifyStatus=REJECTED，记录原因，仍推进到 step3（前端可改信息重提或继续，人脸环节会再校验）
     */
    @Transactional
    public void submitBasicInfo(Long userId, BasicInfoRequest request) {
        VolunteerProfile profile = getOrCreateProfile(userId);

        if (profile.getRegistrationStep() != RegistrationStep.STEP_1_BASIC_INFO) {
            throw new RegistrationStepException("当前步骤不允许提交基本信息，当前步骤：" + profile.getRegistrationStep().name());
        }

        // 基本信息
        profile.setName(request.getName());
        profile.setPhone(request.getPhone());
        profile.setRunningExperience(request.getRunningExperience());
        profile.setHasGuidedBefore(request.getHasGuidedBefore() != null ? request.getHasGuidedBefore() : false);
        profile.setEmergencyExperience(request.getEmergencyExperience());

        // 身份证姓名+号码（原 step2 字段挪到 step1）
        profile.setIdCardName(request.getIdCardName());
        profile.setIdCardNumber(request.getIdCardNumber());

        // Id2Meta 二要素核验（与盲人 verifyIdentity 行为对齐）
        boolean passed = idVerifyService.verifyIdCard(request.getIdCardName(), request.getIdCardNumber());
        if (passed) {
            profile.setIdVerifyStatus(IdVerifyStatus.APPROVED);
            profile.setIdVerifyRejectionReason(null);
            log.info("志愿者 {} 二要素核验通过", userId);
        } else {
            profile.setIdVerifyStatus(IdVerifyStatus.REJECTED);
            profile.setIdVerifyRejectionReason("身份证姓名与号码不一致，请核对后重新提交");
            log.warn("志愿者 {} 二要素核验未通过", userId);
        }

        // 直接推进到 STEP_3（跳过已下线的 step2）
        profile.setRegistrationStep(RegistrationStep.STEP_3_FACE_VERIFY);
        volunteerProfileRepository.save(profile);
    }

    /**
     * 发起动作活体认证（step3 第一段）。
     * 返回阿里云 CertifyUrl，前端打开后由用户完成动作活体。
     */
    @Transactional
    public FaceVerifyInitResponse initFaceVerify(Long userId, FaceVerifyInitRequest request) {
        VolunteerProfile profile = getProfile(userId);

        if (profile.getRegistrationStep() != RegistrationStep.STEP_3_FACE_VERIFY) {
            throw new RegistrationStepException("当前步骤不允许发起人脸认证");
        }
        if (profile.getIdVerifyStatus() == IdVerifyStatus.REJECTED) {
            throw new RegistrationStepException("身份证核验未通过，请先更新身份证信息");
        }

        FaceVerifyInitResult result = faceVerifyService.initFaceVerify(
                profile.getIdCardName(),
                profile.getIdCardNumber(),
                request.getMetaInfo(),
                faceVerifyReturnUrl,
                generateOrderNo(userId)
        );

        if (result.getCertifyId() == null) {
            // 发起失败，不落库 certifyId，允许重试
            return new FaceVerifyInitResponse(null, null, "ERROR", result.getMessage());
        }

        profile.setFaceVerifyCertifyId(result.getCertifyId());
        profile.setFaceVerifyStatus(FaceVerifyStatus.PENDING);
        profile.setFaceVerifyRejectionReason(null);
        volunteerProfileRepository.save(profile);

        log.info("志愿者 {} 发起动作活体认证: certifyId={}", userId, result.getCertifyId());
        return new FaceVerifyInitResponse(result.getCertifyId(), result.getCertifyUrl(), "PENDING", result.getMessage());
    }

    /**
     * 查询动作活体认证结果（step3 第二段，前端轮询）。
     * 防越权：入参 certifyId 必须等于当前 profile 绑定的 certifyId。
     */
    @Transactional
    public FaceVerifyResultResponse queryFaceVerifyResult(Long userId, FaceVerifyResultRequest request) {
        VolunteerProfile profile = getProfile(userId);

        String boundCertifyId = profile.getFaceVerifyCertifyId();
        if (boundCertifyId == null || !boundCertifyId.equals(request.getCertifyId())) {
            throw new RegistrationStepException("认证 ID 不属于当前用户");
        }

        // 幂等：已通过的重复查询不报错
        if (profile.getFaceVerifyStatus() == FaceVerifyStatus.APPROVED) {
            return new FaceVerifyResultResponse(true, "APPROVED", "认证已通过");
        }

        FaceVerifyResult result = faceVerifyService.describeFaceVerify(request.getCertifyId());

        if (result.isPassed()) {
            profile.setFaceVerifyStatus(FaceVerifyStatus.APPROVED);
            profile.setRegistrationStep(RegistrationStep.STEP_4_TRAINING);
            profile.setFaceVerifyRejectionReason(null);
            volunteerProfileRepository.save(profile);
            log.info("志愿者 {} 动作活体认证通过，进入培训阶段", userId);
            return new FaceVerifyResultResponse(true, "APPROVED", "认证通过");
        }

        // PENDING（SERVICE_ERROR/PENDING/NO_RESULT 等非通过非明确失败）→ 保持进行中，前端继续轮询
        if (isPending(result.getSubCode())) {
            return new FaceVerifyResultResponse(false, "PENDING", result.getMessage());
        }

        // 明确失败（subCode 201~209 等）→ REJECTED，允许重新 init（会覆盖 certifyId）
        profile.setFaceVerifyStatus(FaceVerifyStatus.REJECTED);
        profile.setFaceVerifyRejectionReason(result.getMessage());
        volunteerProfileRepository.save(profile);
        log.info("志愿者 {} 动作活体认证未通过: {}", userId, result.getMessage());
        return new FaceVerifyResultResponse(false, "REJECTED", result.getMessage());
    }

    /**
     * 获取注册状态。
     * 历史数据若卡在已下线的 STEP_2_ID_UPLOAD，自动迁移到 STEP_3 并写回。
     */
    @Transactional
    public RegistrationStatusResponse getRegistrationStatus(Long userId) {
        VolunteerProfile profile = getProfile(userId);

        // step2 已下线：历史用户自动迁移到 step3
        if (profile.getRegistrationStep() == RegistrationStep.STEP_2_ID_UPLOAD) {
            log.info("志愿者 {} 历史数据处于已下线的 STEP_2，自动迁移到 STEP_3", userId);
            profile.setRegistrationStep(RegistrationStep.STEP_3_FACE_VERIFY);
            volunteerProfileRepository.save(profile);
        }

        Map<String, Object> details = new HashMap<>();
        details.put("idVerifyStatus", profile.getIdVerifyStatus().name());
        details.put("faceVerifyStatus", profile.getFaceVerifyStatus().name());
        details.put("totalTrainingMinutes", profile.getTotalTrainingMinutes());
        details.put("completedCoursesCount", profile.getCompletedCoursesCount());
        details.put("currentCourseId", profile.getCurrentCourseId());

        if (profile.getIdVerifyStatus() == IdVerifyStatus.REJECTED) {
            details.put("idVerifyRejectionReason", profile.getIdVerifyRejectionReason());
        }
        if (profile.getFaceVerifyStatus() == FaceVerifyStatus.REJECTED) {
            details.put("faceVerifyRejectionReason", profile.getFaceVerifyRejectionReason());
        }

        boolean canAcceptOrders = profile.getRegistrationStep() == RegistrationStep.STEP_4_COMPLETED;
        return new RegistrationStatusResponse(profile.getRegistrationStep(), canAcceptOrders, details);
    }

    // === 私有方法 ===

    /** 判断 describe 结果是否属于"进行中"（前端应继续轮询而非报失败） */
    private boolean isPending(String subCode) {
        if (subCode == null) return true;
        // SERVICE_ERROR / NO_RESULT / PENDING / null 都视为进行中，避免误判失败
        return switch (subCode) {
            case "SERVICE_ERROR", "NO_RESULT", "PENDING" -> true;
            default -> false;
        };
    }

    private VolunteerProfile getProfile(Long userId) {
        return volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在，请先设置为志愿者角色"));
    }

    private VolunteerProfile getOrCreateProfile(Long userId) {
        return volunteerProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    VolunteerProfile profile = new VolunteerProfile();
                    profile.setUserId(userId);
                    profile.setRegistrationStep(RegistrationStep.STEP_1_BASIC_INFO);
                    return volunteerProfileRepository.save(profile);
                });
    }

    private String generateOrderNo(Long userId) {
        return userId + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}

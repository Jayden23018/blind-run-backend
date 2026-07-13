package com.example.demo.service;

import com.example.demo.dto.volunteer.*;
import com.example.demo.entity.*;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.RegistrationRejectedException;
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
 *     → step3 FACE_VERIFY（动作活体：init → 客户端 App SDK 消费 certifyId → result 轮询）
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
     *   通过 → idVerifyStatus=APPROVED，推进到 step3
     *   失败 → idVerifyStatus=REJECTED，**保持 STEP_1**，抛 ID_INFO_INVALID 引导前端修改后重提
     *
     * 规范化：身份证号去空格 + 转大写（前端本地正则放行末尾小写 x / 含空格，
     * 但阿里云二要素与人脸初始化对此敏感，统一在落库前归一化）。
     */
    @Transactional
    public void submitBasicInfo(Long userId, BasicInfoRequest request) {
        VolunteerProfile profile = getOrCreateProfile(userId);

        if (profile.getRegistrationStep() != RegistrationStep.STEP_1_BASIC_INFO) {
            throw new RegistrationStepException("当前步骤不允许提交基本信息，当前步骤：" + profile.getRegistrationStep().name());
        }

        // 身份证姓名/号码规范化：去前后空格，号码统一大写（末尾 X）
        String idCardName = request.getIdCardName().trim();
        String idCardNumber = request.getIdCardNumber().trim().toUpperCase();

        // 基本信息
        profile.setName(request.getName());
        profile.setPhone(request.getPhone());
        profile.setRunningExperience(request.getRunningExperience());
        profile.setHasGuidedBefore(request.getHasGuidedBefore() != null ? request.getHasGuidedBefore() : false);
        profile.setEmergencyExperience(request.getEmergencyExperience());

        // 身份证姓名+号码（规范化后落库）
        profile.setIdCardName(idCardName);
        profile.setIdCardNumber(idCardNumber);

        // Id2Meta 二要素核验：失败即拦截在 STEP_1，不推进到 step3
        boolean passed = idVerifyService.verifyIdCard(idCardName, idCardNumber);
        if (!passed) {
            profile.setIdVerifyStatus(IdVerifyStatus.REJECTED);
            profile.setIdVerifyRejectionReason("身份证姓名与号码不一致，请核对后重新提交");
            volunteerProfileRepository.save(profile);   // 落库 REJECTED 但步骤仍保持 STEP_1
            log.warn("志愿者 {} 二要素核验未通过，保持 STEP_1", userId);
            throw new RegistrationRejectedException(ErrorCode.ID_INFO_INVALID,
                    "身份证信息核验未通过，请核对姓名与身份证号");
        }

        profile.setIdVerifyStatus(IdVerifyStatus.APPROVED);
        profile.setIdVerifyRejectionReason(null);
        profile.setRegistrationStep(RegistrationStep.STEP_3_FACE_VERIFY);
        volunteerProfileRepository.save(profile);
        log.info("志愿者 {} 二要素核验通过，推进到 STEP_3", userId);
    }

    /**
     * 发起动作活体认证（step3 第一段）。
     * 返回阿里云 certifyId，客户端用 App SDK（AliyunFaceAuthFacade）消费完成动作活体；certifyUrl 恒为 null（App SDK 场景不返回）。
     */
    @Transactional
    public FaceVerifyInitResponse initFaceVerify(Long userId, FaceVerifyInitRequest request) {
        VolunteerProfile profile = getProfile(userId);

        if (profile.getRegistrationStep() != RegistrationStep.STEP_3_FACE_VERIFY) {
            throw new RegistrationStepException("当前步骤不允许发起人脸认证");
        }
        // 防御：本不应出现（step1 失败已拦截），但兼容历史脏数据 / 改造前已卡在 step3 的 REJECTED 用户
        if (profile.getIdVerifyStatus() == IdVerifyStatus.REJECTED) {
            profile.setRegistrationStep(RegistrationStep.STEP_1_BASIC_INFO);
            volunteerProfileRepository.save(profile);
            log.warn("志愿者 {} 在 step3 发现身份证 REJECTED，回退到 STEP_1", userId);
            throw new RegistrationRejectedException(ErrorCode.ID_INFO_INVALID,
                    "身份证信息异常，请重新提交基本信息");
        }

        FaceVerifyInitResult result = faceVerifyService.initFaceVerify(
                userId.toString(),
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
        // 阿里云 OuterOrderNo 限制 ≤32 位英文数字，故不用下划线分隔，并截断兜底
        String raw = userId + UUID.randomUUID().toString().replace("-", "");
        return raw.length() > 32 ? raw.substring(0, 32) : raw;
    }
}

package com.example.demo.service;

import com.example.demo.dto.volunteer.*;
import com.example.demo.entity.*;
import com.example.demo.exception.RegistrationStepException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.service.impl.AliyunIdVerifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 志愿者注册流程服务
 */
@Slf4j
@Service
public class VolunteerRegistrationService {

    private final VolunteerProfileRepository volunteerProfileRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final AliyunIdVerifyService idVerifyService;

    public VolunteerRegistrationService(VolunteerProfileRepository volunteerProfileRepository,
                                        UserRepository userRepository,
                                        FileStorageService fileStorageService,
                                        NotificationService notificationService,
                                        ObjectMapper objectMapper,
                                        AliyunIdVerifyService idVerifyService) {
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.idVerifyService = idVerifyService;
    }

    /**
     * 提交基本信息（STEP_1 → STEP_2）
     */
    @Transactional
    public void submitBasicInfo(Long userId, BasicInfoRequest request) {
        VolunteerProfile profile = getOrCreateProfile(userId);

        // 校验：必须在 STEP_1
        if (profile.getRegistrationStep() != RegistrationStep.STEP_1_BASIC_INFO) {
            throw new RegistrationStepException("当前步骤不允许提交基本信息，当前步骤：" + profile.getRegistrationStep().name());
        }

        // 更新字段
        profile.setName(request.getName());
        profile.setPhone(request.getPhone());
        profile.setRunningExperience(request.getRunningExperience());
        profile.setHasGuidedBefore(request.getHasGuidedBefore() != null ? request.getHasGuidedBefore() : false);
        profile.setEmergencyExperience(request.getEmergencyExperience());

        // 自动推进到 STEP_2
        profile.setRegistrationStep(RegistrationStep.STEP_2_ID_UPLOAD);
        volunteerProfileRepository.save(profile);

        log.info("志愿者 {} 提交基本信息", userId);
    }

    /**
     * 上传身份证（STEP_2 → STEP_3）
     */
    @Transactional
    public void uploadIdCard(Long userId, String idCardName, String idCardNumber,
                            MultipartFile frontFile, MultipartFile backFile) {
        VolunteerProfile profile = getProfile(userId);

        // 校验：必须在 STEP_2
        if (profile.getRegistrationStep() != RegistrationStep.STEP_2_ID_UPLOAD) {
            throw new RegistrationStepException("请先完成基本信息填写");
        }

        // 保存文件
        String frontUrl = fileStorageService.store(frontFile);
        String backUrl = fileStorageService.store(backFile);

        // 更新字段
        profile.setIdCardName(idCardName);
        profile.setIdCardNumber(idCardNumber);
        profile.setIdCardFrontUrl(frontUrl);
        profile.setIdCardBackUrl(backUrl);
        profile.setIdVerifyStatus(IdVerifyStatus.PENDING);

        // 推进到 STEP_3
        profile.setRegistrationStep(RegistrationStep.STEP_3_FACE_VERIFY);
        volunteerProfileRepository.save(profile);

        log.info("志愿者 {} 上传身份证，等待管理员审核", userId);
    }

    /**
     * 人脸验证（STEP_3 → STEP_4）
     * 调用阿里云 ContrastFaceVerify API 进行照片比对
     */
    @Transactional
    public FaceVerifyInitResponse initFaceVerify(Long userId, MultipartFile facePhoto) {
        VolunteerProfile profile = getProfile(userId);

        // 校验：必须在 STEP_3
        if (profile.getRegistrationStep() != RegistrationStep.STEP_3_FACE_VERIFY) {
            throw new RegistrationStepException("当前步骤不允许进行人脸验证");
        }

        // 校验：身份证必须已通过
        if (profile.getIdVerifyStatus() != IdVerifyStatus.APPROVED) {
            throw new RegistrationStepException("请先完成身份证审核");
        }

        // 保存人脸照片
        String facePhotoUrl = fileStorageService.store(facePhoto);
        profile.setFacePhotoUrl(facePhotoUrl);

        // 调用阿里云人脸比对
        try {
            AliyunIdVerifyService.FaceVerifyResult result = idVerifyService.contrastFaceVerify(
                    profile.getIdCardName(),
                    profile.getIdCardNumber(),
                    facePhoto.getBytes()
            );

            if (result.isPassed()) {
                profile.setFaceVerifyStatus(FaceVerifyStatus.APPROVED);
                profile.setRegistrationStep(RegistrationStep.STEP_4_TRAINING);
                profile.setFaceVerifyRejectionReason(null);
                volunteerProfileRepository.save(profile);

                log.info("志愿者 {} 人脸验证通过，进入培训阶段", userId);
                return new FaceVerifyInitResponse(true, "PASSED", "人脸验证通过");
            } else {
                profile.setFaceVerifyStatus(FaceVerifyStatus.REJECTED);
                profile.setFaceVerifyRejectionReason(result.getMessage());
                volunteerProfileRepository.save(profile);

                log.info("志愿者 {} 人脸验证未通过: {}", userId, result.getMessage());
                return new FaceVerifyInitResponse(false, "REJECTED", result.getMessage());
            }
        } catch (Exception e) {
            // 阿里云服务异常时不改变状态，允许重试
            log.error("志愿者 {} 人脸验证服务异常: {}", userId, e.getMessage(), e);
            throw new RuntimeException("人脸验证服务暂时不可用，请稍后重试", e);
        }
    }

    /**
     * 获取注册状态
     */
    public RegistrationStatusResponse getRegistrationStatus(Long userId) {
        VolunteerProfile profile = getProfile(userId);

        Map<String, Object> details = new HashMap<>();
        details.put("idVerifyStatus", profile.getIdVerifyStatus().name());
        details.put("faceVerifyStatus", profile.getFaceVerifyStatus().name());
        details.put("totalTrainingMinutes", profile.getTotalTrainingMinutes());
        details.put("completedCoursesCount", profile.getCompletedCoursesCount());
        details.put("currentCourseId", profile.getCurrentCourseId());

        // 身份证拒绝原因
        if (profile.getIdVerifyStatus() == IdVerifyStatus.REJECTED) {
            details.put("idVerifyRejectionReason", profile.getIdVerifyRejectionReason());
        }

        // 人脸验证拒绝原因
        if (profile.getFaceVerifyStatus() == FaceVerifyStatus.REJECTED) {
            details.put("faceVerifyRejectionReason", profile.getFaceVerifyRejectionReason());
        }

        boolean canAcceptOrders = profile.getRegistrationStep() == RegistrationStep.STEP_4_COMPLETED;

        return new RegistrationStatusResponse(profile.getRegistrationStep(), canAcceptOrders, details);
    }

    // === 私有方法 ===

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
}

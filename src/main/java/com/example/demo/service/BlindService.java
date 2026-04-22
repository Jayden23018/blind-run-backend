package com.example.demo.service;

import com.example.demo.dto.BlindProfileResponse;
import com.example.demo.dto.BlindProfileUpdateRequest;
import com.example.demo.entity.BlindProfile;
import com.example.demo.entity.BlindVerifyStatus;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.impl.AliyunIdVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 盲人资料业务逻辑服务
 * 注意：紧急联系人已迁移到 EmergencyContactService
 */
@Slf4j
@Service
public class BlindService {

    private final BlindProfileRepository blindProfileRepository;
    private final UserRepository userRepository;
    private final AliyunIdVerifyService idVerifyService;

    public BlindService(BlindProfileRepository blindProfileRepository,
                        UserRepository userRepository,
                        AliyunIdVerifyService idVerifyService) {
        this.blindProfileRepository = blindProfileRepository;
        this.userRepository = userRepository;
        this.idVerifyService = idVerifyService;
    }

    /**
     * 获取盲人资料
     */
    public BlindProfileResponse getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (user.getRole() != UserRole.BLIND) {
            throw new PermissionDeniedException("仅盲人用户可访问此接口");
        }

        BlindProfile profile = blindProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("盲人资料不存在"));

        return toResponse(profile);
    }

    /**
     * 更新盲人资料（upsert）
     */
    @Transactional
    public BlindProfileResponse updateProfile(Long userId, BlindProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (user.getRole() != UserRole.BLIND) {
            throw new PermissionDeniedException("仅盲人用户可访问此接口");
        }

        BlindProfile profile = blindProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    BlindProfile p = new BlindProfile();
                    p.setUser(user);
                    return p;
                });

        profile.setName(request.getName());
        profile.setRunningPace(request.getRunningPace());
        profile.setSpecialNeeds(request.getSpecialNeeds());
        if (request.getVisionLevel() != null) profile.setVisionLevel(request.getVisionLevel());
        if (request.getHasGuideDog() != null) profile.setHasGuideDog(request.getHasGuideDog());
        if (request.getTetherPreference() != null) profile.setTetherPreference(request.getTetherPreference());
        if (request.getChatPreference() != null) profile.setChatPreference(request.getChatPreference());
        if (request.getDefaultPace() != null) profile.setDefaultPace(request.getDefaultPace());

        blindProfileRepository.save(profile);

        return toResponse(profile);
    }

    /**
     * 盲人身份认证（二要素核验）
     */
    @Transactional
    public BlindProfileResponse verifyIdentity(Long userId, String idCardName, String idCardNumber) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (user.getRole() != UserRole.BLIND) {
            throw new PermissionDeniedException("仅盲人用户可访问此接口");
        }

        BlindProfile profile = blindProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    BlindProfile p = new BlindProfile();
                    p.setUser(user);
                    return p;
                });

        try {
            boolean passed = idVerifyService.verifyIdCard(idCardName, idCardNumber);

            if (passed) {
                profile.setIdCardName(idCardName);
                profile.setIdCardNumber(idCardNumber);
                profile.setVerifyStatus(BlindVerifyStatus.VERIFIED);
                log.info("盲人用户 {} 身份认证通过", userId);
            } else {
                profile.setVerifyStatus(BlindVerifyStatus.FAILED);
                log.info("盲人用户 {} 身份认证未通过", userId);
            }
        } catch (Exception e) {
            profile.setVerifyStatus(BlindVerifyStatus.FAILED);
            log.error("盲人用户 {} 身份认证服务异常: {}", userId, e.getMessage(), e);
            throw new RuntimeException("身份认证服务暂时不可用，请稍后重试", e);
        }

        blindProfileRepository.save(profile);
        return toResponse(profile);
    }

    private BlindProfileResponse toResponse(BlindProfile profile) {
        return new BlindProfileResponse(
                profile.getName(),
                profile.getRunningPace(),
                profile.getSpecialNeeds(),
                profile.getVerifyStatus(),
                profile.getVisionLevel(),
                profile.getHasGuideDog(),
                profile.getTetherPreference(),
                profile.getChatPreference(),
                profile.getDefaultPace()
        );
    }
}

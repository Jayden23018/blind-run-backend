package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.entity.VolunteerProfile;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.OrderStatusException;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.EmergencyContactRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.RunOrderTrackPointRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerAvailableTimeRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.util.PhoneMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户业务逻辑服务
 */
@Slf4j
@Service
public class UserService {

    /** 盲人侧：这些状态下志愿者仍在响应/陪跑，注销会让对方悬空 */
    private static final List<OrderStatus> BLIND_ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.IN_PROGRESS,
            OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED, OrderStatus.REMATCHING);

    /**
     * 志愿者侧：不含 PENDING_MATCH/REMATCHING —— 这两个状态下 order.volunteer 已被
     * OrderLifecycleService 置空，志愿者已不再关联该订单
     */
    private static final List<OrderStatus> VOLUNTEER_ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.PENDING_ACCEPT, OrderStatus.IN_PROGRESS,
            OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED);

    private final UserRepository userRepository;
    private final RunOrderRepository runOrderRepository;
    private final TokenBlacklistService tokenBlacklistService;
    private final BlindProfileRepository blindProfileRepository;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final VolunteerAvailableTimeRepository volunteerAvailableTimeRepository;
    private final RunOrderTrackPointRepository runOrderTrackPointRepository;
    private final FileStorageService fileStorageService;

    public UserService(UserRepository userRepository, RunOrderRepository runOrderRepository,
                       TokenBlacklistService tokenBlacklistService,
                       BlindProfileRepository blindProfileRepository,
                       VolunteerProfileRepository volunteerProfileRepository,
                       EmergencyContactRepository emergencyContactRepository,
                       VolunteerAvailableTimeRepository volunteerAvailableTimeRepository,
                       RunOrderTrackPointRepository runOrderTrackPointRepository,
                       FileStorageService fileStorageService) {
        this.userRepository = userRepository;
        this.runOrderRepository = runOrderRepository;
        this.tokenBlacklistService = tokenBlacklistService;
        this.blindProfileRepository = blindProfileRepository;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.emergencyContactRepository = emergencyContactRepository;
        this.volunteerAvailableTimeRepository = volunteerAvailableTimeRepository;
        this.runOrderTrackPointRepository = runOrderTrackPointRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 获取用户信息（只能查自己）
     */
    public Map<String, Object> getUserInfo(Long targetUserId, Long currentUserId) {
        if (!targetUserId.equals(currentUserId)) {
            throw new PermissionDeniedException("只能查看自己的信息");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        if (user.getDeletedAt() != null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        return Map.of(
                "userId", user.getId(),
                "phone", PhoneMaskUtils.mask(user.getPhone()),
                "role", user.getRole() != null ? user.getRole().name() : "UNSET",
                "createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : ""
        );
    }

    /**
     * 注销账号（软删除）
     */
    @Transactional
    public void deleteAccount(Long targetUserId, Long currentUserId) {
        if (!targetUserId.equals(currentUserId)) {
            throw new PermissionDeniedException("只能注销自己的账号");
        }

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

        // 检查是否有进行中的订单（盲人侧 + 志愿者侧，两种角色都要拦，防止对方被晾在半空）
        boolean hasActiveOrder = runOrderRepository.existsByBlindUserIdAndStatusIn(targetUserId, BLIND_ACTIVE_ORDER_STATUSES)
                || runOrderRepository.existsByVolunteerIdAndStatusIn(targetUserId, VOLUNTEER_ACTIVE_ORDER_STATUSES);
        if (hasActiveOrder) {
            throw new OrderStatusException(ErrorCode.ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED, "您有进行中的订单，无法注销");
        }

        user.setDeletedAt(LocalDateTime.now());
        // 释放手机号：phone 列有唯一约束，软删除不会自动腾出手机号，
        // 不改的话同一手机号注销后永远无法重新注册（撞唯一键报 500）
        user.setPhone("deleted_" + user.getId() + "_" + user.getPhone());
        user.setName(null);
        userRepository.save(user);

        cascadeDeletePii(targetUserId, user.getRole());

        // 立即使该用户的所有 token 失效
        tokenBlacklistService.blacklistUserWithMaxTtl(targetUserId);
    }

    /**
     * 级联清理注销用户的 PII：资料表、紧急联系人、可服务时间、轨迹点、OSS 证件照片。
     * RunOrder/OrderReview/EmergencyEvent 等两方记录不删除（不直接存储姓名/手机号，
     * User 本身已匿名化即可满足最小必要原则，保留记录用于纠纷复核/审计）。
     */
    private void cascadeDeletePii(Long userId, UserRole role) {
        if (role == UserRole.BLIND) {
            blindProfileRepository.deleteByUserId(userId);
            emergencyContactRepository.deleteByUserId(userId);
        } else if (role == UserRole.VOLUNTEER) {
            volunteerProfileRepository.findByUserId(userId)
                    .map(VolunteerProfile::getVerificationDocUrl)
                    .filter(url -> !url.isBlank())
                    .ifPresent(url -> {
                        try {
                            fileStorageService.delete(url);
                        } catch (Exception e) {
                            log.warn("注销账号清理证件照片失败，不阻断注销流程 userId={}", userId, e);
                        }
                    });
            volunteerProfileRepository.deleteByUserId(userId);
            volunteerAvailableTimeRepository.deleteByVolunteerId(userId);
        }
        runOrderTrackPointRepository.deleteByUserId(userId);
    }
}

package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.User;
import com.example.demo.exception.OrderStatusException;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.util.PhoneMaskUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户业务逻辑服务
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RunOrderRepository runOrderRepository;
    private final TokenBlacklistService tokenBlacklistService;

    public UserService(UserRepository userRepository, RunOrderRepository runOrderRepository,
                       TokenBlacklistService tokenBlacklistService) {
        this.userRepository = userRepository;
        this.runOrderRepository = runOrderRepository;
        this.tokenBlacklistService = tokenBlacklistService;
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

        // 检查是否有进行中的订单
        boolean hasActiveOrder = runOrderRepository.existsByBlindUserIdAndStatusIn(
                targetUserId,
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT,
                        OrderStatus.IN_PROGRESS, OrderStatus.REMATCHING)
        );
        if (hasActiveOrder) {
            throw new OrderStatusException("您有进行中的订单，无法注销");
        }

        user.setDeletedAt(LocalDateTime.now());
        // 释放手机号：phone 列有唯一约束，软删除不会自动腾出手机号，
        // 不改的话同一手机号注销后永远无法重新注册（撞唯一键报 500）
        user.setPhone("deleted_" + user.getId() + "_" + user.getPhone());
        userRepository.save(user);

        // 立即使该用户的所有 token 失效
        tokenBlacklistService.blacklistUserWithMaxTtl(targetUserId);
    }
}

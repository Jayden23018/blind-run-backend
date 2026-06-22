package com.example.demo.service;

import com.example.demo.dto.EmergencyContactRequest;
import com.example.demo.dto.EmergencyContactResponse;
import com.example.demo.entity.EmergencyContact;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.EmergencyContactRepository;
import com.example.demo.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 紧急联系人管理服务
 * 每个盲人用户可设置 1~5 个紧急联系人，有且仅有一个 is_primary=true
 */
@Slf4j
@Service
public class EmergencyContactService {

    private final EmergencyContactRepository contactRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public EmergencyContactService(EmergencyContactRepository contactRepository,
                                   UserRepository userRepository,
                                   NotificationService notificationService) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /** 获取用户的所有紧急联系人 */
    public List<EmergencyContactResponse> getContacts(Long userId) {
        validateBlindUser(userId);
        return contactRepository.findByUserIdOrderByIsPrimaryDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /** 新增紧急联系人 */
    @Transactional
    public EmergencyContactResponse addContact(Long userId, EmergencyContactRequest request) {
        validateBlindUser(userId);

        long count = contactRepository.countByUserId(userId);
        if (count >= 5) {
            throw new IllegalStateException("最多添加 5 个紧急联系人");
        }

        // 如果是第一个联系人，自动设为主要联系人
        if (count == 0) {
            request.setIsPrimary(true);
        }

        // 如果设为主要联系人，取消其他人的主要标记
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            clearPrimaryFlag(userId);
        }

        EmergencyContact contact = new EmergencyContact();
        contact.setUserId(userId);
        contact.setName(request.getName());
        contact.setPhone(request.getPhone());
        contact.setRelationship(request.getRelationship());
        contact.setIsPrimary(Boolean.TRUE.equals(request.getIsPrimary()));

        contactRepository.save(contact);
        log.info("用户 {} 新增紧急联系人: {}", userId, contact.getName());

        // 通知被添加的联系人
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            notificationService.sendContactAddedSms(contact.getPhone(), user.getName());
        }

        return toResponse(contact);
    }

    /** 修改紧急联系人 */
    @Transactional
    public EmergencyContactResponse updateContact(Long userId, Long contactId, EmergencyContactRequest request) {
        validateBlindUser(userId);

        EmergencyContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("联系人不存在"));
        if (!contact.getUserId().equals(userId)) {
            throw new PermissionDeniedException("无权操作此联系人");
        }

        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            clearPrimaryFlag(userId);
        }

        contact.setName(request.getName());
        contact.setPhone(request.getPhone());
        contact.setRelationship(request.getRelationship());
        if (request.getIsPrimary() != null) {
            contact.setIsPrimary(request.getIsPrimary());
        }

        contactRepository.save(contact);
        return toResponse(contact);
    }

    /** 删除紧急联系人（仅剩 1 个时拒绝） */
    @Transactional
    public void deleteContact(Long userId, Long contactId) {
        validateBlindUser(userId);

        EmergencyContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("联系人不存在"));
        if (!contact.getUserId().equals(userId)) {
            throw new PermissionDeniedException("无权操作此联系人");
        }

        long count = contactRepository.countByUserId(userId);
        if (count <= 1) {
            throw new IllegalStateException("至少保留 1 个紧急联系人");
        }

        contactRepository.delete(contact);

        // 如果删除的是主要联系人，将第一个设为主要
        if (Boolean.TRUE.equals(contact.getIsPrimary())) {
            List<EmergencyContact> remaining = contactRepository.findByUserIdOrderByIsPrimaryDesc(userId);
            if (!remaining.isEmpty()) {
                remaining.get(0).setIsPrimary(true);
                contactRepository.save(remaining.get(0));
            }
        }
    }

    /** 设为主要联系人 */
    @Transactional
    public void setPrimary(Long userId, Long contactId) {
        validateBlindUser(userId);

        EmergencyContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException("联系人不存在"));
        if (!contact.getUserId().equals(userId)) {
            throw new PermissionDeniedException("无权操作此联系人");
        }

        clearPrimaryFlag(userId);
        contact.setIsPrimary(true);
        contactRepository.save(contact);
    }

    /** 检查用户是否已设置紧急联系人（下单前置校验） */
    public boolean hasContacts(Long userId) {
        return contactRepository.countByUserId(userId) > 0;
    }

    // === 私有方法 ===

    private void validateBlindUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        if (user.getRole() != UserRole.BLIND) {
            throw new PermissionDeniedException("仅盲人用户可管理紧急联系人");
        }
    }

    private void clearPrimaryFlag(Long userId) {
        contactRepository.findByUserIdAndIsPrimaryTrue(userId).ifPresent(c -> {
            c.setIsPrimary(false);
            contactRepository.save(c);
        });
    }

    private EmergencyContactResponse toResponse(EmergencyContact contact) {
        EmergencyContactResponse resp = new EmergencyContactResponse();
        resp.setId(contact.getId());
        resp.setName(contact.getName());
        resp.setPhone(contact.getPhone());
        resp.setRelationship(contact.getRelationship());
        resp.setIsPrimary(contact.getIsPrimary());
        return resp;
    }
}

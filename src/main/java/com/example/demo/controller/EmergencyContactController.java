package com.example.demo.controller;

import com.example.demo.dto.EmergencyContactRequest;
import com.example.demo.dto.EmergencyContactResponse;
import com.example.demo.service.EmergencyContactService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 紧急联系人管理控制器
 */
@RestController
@RequestMapping("/api/users/{userId}/emergency-contacts")
public class EmergencyContactController {

    private final EmergencyContactService contactService;

    public EmergencyContactController(EmergencyContactService contactService) {
        this.contactService = contactService;
    }

    /** 获取所有联系人 */
    @GetMapping
    public ResponseEntity<List<EmergencyContactResponse>> getContacts(@PathVariable Long userId) {
        verifyUser(userId);
        return ResponseEntity.ok(contactService.getContacts(userId));
    }

    /** 新增联系人 */
    @PostMapping
    public ResponseEntity<EmergencyContactResponse> addContact(
            @PathVariable Long userId, @Valid @RequestBody EmergencyContactRequest request) {
        verifyUser(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(contactService.addContact(userId, request));
    }

    /** 修改联系人 */
    @PutMapping("/{contactId}")
    public ResponseEntity<EmergencyContactResponse> updateContact(
            @PathVariable Long userId, @PathVariable Long contactId,
            @Valid @RequestBody EmergencyContactRequest request) {
        verifyUser(userId);
        return ResponseEntity.ok(contactService.updateContact(userId, contactId, request));
    }

    /** 删除联系人 */
    @DeleteMapping("/{contactId}")
    public ResponseEntity<?> deleteContact(@PathVariable Long userId, @PathVariable Long contactId) {
        verifyUser(userId);
        contactService.deleteContact(userId, contactId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 设为主要联系人 */
    @PutMapping("/{contactId}/set-primary")
    public ResponseEntity<?> setPrimary(@PathVariable Long userId, @PathVariable Long contactId) {
        verifyUser(userId);
        contactService.setPrimary(userId, contactId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 校验当前 JWT 用户与路径参数一致 */
    private void verifyUser(Long userId) {
        Long currentUserId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!currentUserId.equals(userId)) {
            throw new SecurityException("无权操作此用户的联系人");
        }
    }
}

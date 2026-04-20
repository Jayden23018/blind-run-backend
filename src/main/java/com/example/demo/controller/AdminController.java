package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.UpdateNotificationTemplateRequest;
import com.example.demo.entity.CSUser;
import com.example.demo.entity.NotificationTemplate;
import com.example.demo.repository.CSUserRepository;
import com.example.demo.repository.NotificationTemplateRepository;
import jakarta.validation.Valid;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员控制器 —— 处理通知模板管理
 *
 * 所有接口需要管理员 JWT 鉴权（csRole=ADMIN）
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final NotificationTemplateRepository templateRepository;
    private final CSUserRepository csUserRepository;

    public AdminController(NotificationTemplateRepository templateRepository,
                           CSUserRepository csUserRepository) {
        this.templateRepository = templateRepository;
        this.csUserRepository = csUserRepository;
    }

    /**
     * 从 SecurityContext 提取客服用户，并验证是 ADMIN 角色
     */
    private CSUser requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("未认证");
        }
        // details 中存的是 csRole，如果没有则不是客服 token
        if (auth.getDetails() == null || !(auth.getDetails() instanceof String)) {
            throw new RuntimeException("需要管理员权限");
        }
        Long csUserId = (Long) auth.getPrincipal();
        CSUser csUser = csUserRepository.findById(csUserId)
                .orElseThrow(() -> new RuntimeException("客服用户不存在"));

        if (csUser.getRole() != CSUser.CSRole.ADMIN) {
            throw new RuntimeException("需要管理员权限，当前角色为：" + csUser.getRole());
        }
        return csUser;
    }

    /**
     * 获取所有通知模板
     */
    @GetMapping("/notification-templates")
    public ResponseEntity<?> getAllTemplates() {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        List<NotificationTemplate> templates = templateRepository.findByIsActiveTrue();
        return ResponseEntity.ok(ApiResponse.ok(templates));
    }

    /**
     * 更新通知模板
     * 只允许修改 template_text 和 tts_text，不允许改 event_type
     */
    @PutMapping("/notification-templates/{id}")
    @CacheEvict(value = "notificationTemplates", allEntries = true)
    public ResponseEntity<?> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateNotificationTemplateRequest request) {
        try {
            requireAdmin();
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, e.getMessage()));
        }

        NotificationTemplate template = templateRepository.findById(id)
                .orElse(null);
        if (template == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "模板不存在"));
        }

        // 只允许修改 template_text 和 tts_text
        template.setTemplateText(request.getTemplateText());
        template.setTtsText(request.getTtsText());

        templateRepository.save(template);

        return ResponseEntity.ok(ApiResponse.ok(template));
    }
}

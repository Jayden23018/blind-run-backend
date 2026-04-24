package com.example.demo.controller;

import com.example.demo.service.UserService;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import com.example.demo.util.SecurityUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理控制器
 *
 * GET    /api/users/{id} → 查询用户信息（只能查自己）
 * DELETE /api/users/{id} → 注销账号（软删除）
 */
@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable @Min(1) Long id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Map<String, Object> userInfo = userService.getUserInfo(id, currentUserId);
        return ResponseEntity.ok(userInfo);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable @Min(1) Long id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        userService.deleteAccount(id, currentUserId);
        return ResponseEntity.ok(Map.of("success", true));
    }
}

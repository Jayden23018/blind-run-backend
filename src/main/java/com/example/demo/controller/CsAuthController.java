package com.example.demo.controller;

import com.example.demo.service.CSAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客服认证控制器
 */
@RestController
@RequestMapping("/api/cs/auth")
public class CsAuthController {

    private final CSAuthService csAuthService;

    public CsAuthController(CSAuthService csAuthService) {
        this.csAuthService = csAuthService;
    }

    /**
     * 客服登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }

        try {
            String[] result = csAuthService.login(username, password);
            return ResponseEntity.ok(Map.of(
                    "token", result[0],
                    "role", result[1]
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

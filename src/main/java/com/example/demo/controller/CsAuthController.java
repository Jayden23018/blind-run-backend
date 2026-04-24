package com.example.demo.controller;

import com.example.demo.dto.CsLoginRequest;
import com.example.demo.service.CSAuthService;
import com.example.demo.service.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final TokenBlacklistService tokenBlacklistService;

    public CsAuthController(CSAuthService csAuthService, TokenBlacklistService tokenBlacklistService) {
        this.csAuthService = csAuthService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 客服登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody CsLoginRequest request) {
        try {
            String[] result = csAuthService.login(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(Map.of(
                    "token", result[0],
                    "role", result[1]
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 客服登出
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokenBlacklistService.blacklistUserFromToken(header.substring(7));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}

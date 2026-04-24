package com.example.demo.util;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具 —— 从 SecurityContext 提取当前用户信息
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** 获取当前认证用户的 userId */
    public static Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}

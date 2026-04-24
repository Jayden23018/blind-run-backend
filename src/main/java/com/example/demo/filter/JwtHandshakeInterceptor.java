package com.example.demo.filter;

import com.example.demo.service.TokenBlacklistService;
import com.example.demo.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器 —— 在 WebSocket 连接建立前验证 JWT token + 用户角色
 *
 * 认证流程：
 * 1. 从 URL query 参数提取 token（浏览器无法设置 WS header）
 * 2. 解析并验证 token 有效性
 * 3. 检查 token 黑名单（登出/注销后拒绝连接）
 * 4. 校验用户角色与端点匹配（盲人不能连 /ws/volunteer，反之亦然）
 */
@Slf4j
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtHandshakeInterceptor(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null || !query.contains("token=")) {
            log.warn("WebSocket 握手失败：缺少 token 参数");
            return false;
        }

        String token = null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                token = param.substring(6);
                break;
            }
        }

        if (token == null || token.isEmpty()) {
            log.warn("WebSocket 握手失败：token 为空");
            return false;
        }

        // 单次解析 token，提取 userId 和 role
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            log.warn("WebSocket 握手失败：token 无效或已过期");
            return false;
        }

        try {
            Long userId = Long.parseLong(claims.getSubject());

            // 检查 token 黑名单
            if (tokenBlacklistService.isBlacklisted(userId)) {
                log.warn("WebSocket 握手失败：用户 {} token 已被吊销", userId);
                return false;
            }

            // 校验用户角色与端点匹配
            String role = claims.get("role", String.class);
            String path = request.getURI().getPath();
            if (!isRoleAllowedForEndpoint(role, path)) {
                log.warn("WebSocket 握手失败：用户 {} 角色 {} 无权连接 {}", userId, role, path);
                return false;
            }

            attributes.put("userId", userId);
            log.info("WebSocket 握手成功：用户 {} 角色 {} 连接 {}", userId, role, path);
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 握手失败：解析 token 出错 - {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验用户角色是否允许连接目标端点
     * - /ws/volunteer → 需要 VOLUNTEER 角色
     * - /ws/blind     → 需要 BLIND 角色
     */
    private boolean isRoleAllowedForEndpoint(String role, String path) {
        if (role == null) {
            return false;
        }
        if (path.contains("/ws/volunteer")) {
            return "VOLUNTEER".equals(role);
        }
        if (path.contains("/ws/blind")) {
            return "BLIND".equals(role);
        }
        // 未知端点默认拒绝
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // 握手完成后的回调，不需要处理
    }
}

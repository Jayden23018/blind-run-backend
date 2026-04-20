package com.example.demo.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Set;

/**
 * JWT 工具类 —— 负责生成和验证 JWT 令牌
 *
 * 【什么是 JWT？】
 * JWT（JSON Web Token）是一种安全的身份认证方式。
 * 可以把它想象成一张 "加密的电子身份证"，里面写着 "这个人是手机号 138xxxx 的用户"。
 *
 * 工作流程：
 * 1. 用户登录成功 → JwtUtil 生成 token（相当于签发身份证）
 * 2. 前端保存 token，每次请求都带上
 * 3. 后端收到请求 → JwtUtil 验证 token（相当于查验身份证）
 *
 * 【@Component 是什么？】
 * 告诉 Spring："请帮我管理这个类的对象"。
 * 这样其他地方就可以通过构造函数注入来使用它，不需要手动 new。
 */
@Slf4j
@Component
public class JwtUtil {

    /** 已知的开发环境默认密钥，生产环境不应使用 */
    private static final Set<String> WEAK_DEFAULTS = Set.of(
            "PleaseReplaceWithAStrongSecretKeyAtLeast32BytesLong!!",
            "DevOnlySecretKey_DoNotUseInProd_2024!@#$RandomBytes"
    );

    /**
     * JWT 签名密钥 —— 用于加密和解密 token
     *
     * 开发环境通过 application.properties 提供默认密钥，生产环境必须通过环境变量 JWT_SECRET 覆盖
     * 密钥要求至少 256 位（32 字节）
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Token 过期时间（毫秒）
     * 默认 86400000 毫秒 = 24 小时
     */
    @Value("${jwt.expiration:86400000}")
    private long expiration;

    /**
     * 根据密钥字符串生成加密密钥对象
     */
    private SecretKey getSigningKey() {
        if (WEAK_DEFAULTS.contains(secret)) {
            log.warn("⚠️ JWT 密钥使用开发默认值，生产环境必须通过环境变量 JWT_SECRET 设置强随机密钥！");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT 密钥长度不足 32 字节，请检查 JWT_SECRET 环境变量");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * 生成 JWT Token
     *
     * @param userId 用户ID（作为 token 的主体标识）
     * @return 生成的 JWT 字符串
     *
     * 生成的 token 结构：
     *   header.payload.signature
     *   header  = { 算法信息 }
     *   payload = { subject: "用户ID", issuedAt: 签发时间, expiration: 过期时间 }
     *   signature = 用密钥对前两部分进行签名，防止篡改
     */
    public String generateToken(Long userId) {
        return generateToken(userId, null);
    }

    /**
     * 生成 JWT Token（带额外 claim，用于客服账号）
     *
     * @param userId 用户ID
     * @param csRole 客服角色（CS/ADMIN），普通用户传 null
     */
    public String generateToken(Long userId, String csRole) {
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration));

        if (csRole != null) {
            builder.claim("csRole", csRole);
        }

        return builder.signWith(getSigningKey()).compact();
    }

    /**
     * 从 token 中提取客服角色（仅客服 token 有此字段）
     */
    public String getCsRoleFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("csRole", String.class);
    }

    /**
     * 从 token 中提取用户ID
     *
     * @param token JWT 字符串
     * @return 用户ID
     */
    public Long getUserIdFromToken(String token) {
        String subject = Jwts.parser()
                .verifyWith(getSigningKey())                       // 用密钥验证签名
                .build()
                .parseSignedClaims(token)                         // 解析 token
                .getPayload()
                .getSubject();                                    // 取出主体（用户ID字符串）
        return Long.parseLong(subject);                           // 转为 Long 类型
    }

    /**
     * 解析 token 并返回 Claims（单次解析，避免重复开销）
     *
     * @param token JWT 字符串
     * @return Claims 载荷对象，解析失败返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 验证 token 是否有效
     *
     * @param token JWT 字符串
     * @return true = 有效，false = 无效（过期、被篡改、格式错误等）
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 如果解析失败（过期、签名不匹配等），返回 false
            return false;
        }
    }
}

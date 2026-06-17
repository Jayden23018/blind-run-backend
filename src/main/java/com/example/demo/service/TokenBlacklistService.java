package com.example.demo.service;

import com.example.demo.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务 —— 基于 Redis 实现 JWT 吊销
 *
 * Redis key: jwt:blacklist:{userId}
 * TTL: token 剩余有效期（自动过期清理）
 *
 * 适用场景：
 * - 用户主动登出
 * - 用户注销账号（软删除）
 * - 管理员封禁账号
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String REDIS_KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final long jwtExpirationMs;

    public TokenBlacklistService(StringRedisTemplate redisTemplate,
                                  JwtUtil jwtUtil,
                                  @Value("${jwt.expiration:86400000}") long jwtExpirationMs) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.jwtExpirationMs = jwtExpirationMs;
    }

    /**
     * 将用户加入黑名单（指定 TTL）
     */
    public void blacklistUser(Long userId, long remainingSeconds) {
        long ttl = Math.max(1, remainingSeconds);
        String key = REDIS_KEY_PREFIX + userId;
        // 存"拉黑时刻"的时间戳：此刻之前签发（iat ≤ 该时间戳）的 token 全部失效，
        // 之后重新登录签发的新 token（iat 更大）不受影响 —— 避免登出后重新登录被锁死。
        // 用 set 覆盖写（非 setIfAbsent），保证每次登出都刷新为最新时刻。
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), ttl, TimeUnit.SECONDS);
        log.info("用户 {} 已加入 token 黑名单（按签发时间吊销），TTL={}秒", userId, ttl);
    }

    /**
     * 检查 token 是否被吊销
     *
     * <p>采用"按签发时间比对"：只有签发时间早于或等于"拉黑时刻"的 token 才被判定为已吊销。
     * 这样用户登出后重新登录拿到的新 token（签发时间更晚）不会被旧黑名单误伤。
     *
     * @param userId   用户ID
     * @param issuedAt token 的签发时间（JWT iat）
     * @return true = 已吊销，禁止通行
     */
    public boolean isBlacklisted(Long userId, Date issuedAt) {
        String key = REDIS_KEY_PREFIX + userId;
        String blacklistedAtStr = redisTemplate.opsForValue().get(key);
        if (blacklistedAtStr == null) {
            return false;
        }
        if (issuedAt == null) {
            // 无签发时间信息，保守判定为已吊销
            return true;
        }
        long blacklistedAt = Long.parseLong(blacklistedAtStr);
        return issuedAt.getTime() <= blacklistedAt;
    }

    /**
     * 从 token 解析 userId 和过期时间，加入黑名单
     * 用于登出场景：前端传 token，后端解析后拉黑
     */
    public void blacklistUserFromToken(String token) {
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            return;
        }
        Long userId = Long.parseLong(claims.getSubject());
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return;
        }
        blacklistUser(userId, TimeUnit.MILLISECONDS.toSeconds(remainingMs));
    }

    /**
     * 用最大 TTL 拉黑用户（用于注销账号、管理员封禁等无 token 可解析的场景）
     */
    public void blacklistUserWithMaxTtl(Long userId) {
        blacklistUser(userId, TimeUnit.MILLISECONDS.toSeconds(jwtExpirationMs));
    }
}

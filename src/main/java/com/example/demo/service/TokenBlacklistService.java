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
 * 两套独立机制，互不干扰：
 * - 按用户整体撤销：key jwt:blacklist:{userId}，值为"拉黑时刻"时间戳，iat ≤ 该时刻的 token 全部失效。
 *   用于注销账号 / 管理员封禁 —— 这类场景没有单个 token 可指向，必须撤销该用户名下所有 token。
 * - 按单个 token 撤销：key jwt:blacklist:jti:{jti}，TTL=该 token 剩余有效期。
 *   用于登出 —— 只吊销当前这一个 token，同账号其他仍在有效期内的 token（如角色选择后签发的替换 token）不受影响。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String REDIS_KEY_PREFIX = "jwt:blacklist:";
    private static final String TOKEN_JTI_PREFIX = "jwt:blacklist:jti:";

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
     * 登出场景：只撤销当前这一个 token（按 jti），不影响同账号其他有效 token
     *
     * <p>升级前签发、没有 jti 的旧 token 无法单独定位，退化为按用户整体撤销
     * （该批旧 token 会在 TTL 内自然过期，影响范围随时间收敛）。
     */
    public void blacklistToken(String token) {
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            return;
        }
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return;
        }
        String jti = claims.getId();
        long ttl = TimeUnit.MILLISECONDS.toSeconds(remainingMs);
        if (jti == null || jti.isBlank()) {
            blacklistUser(Long.parseLong(claims.getSubject()), ttl);
            return;
        }
        redisTemplate.opsForValue().set(TOKEN_JTI_PREFIX + jti, "1", Math.max(1, ttl), TimeUnit.SECONDS);
        log.info("token（jti={}）已加入黑名单（单 token 吊销），TTL={}秒", jti, ttl);
    }

    /**
     * 检查单个 token 是否被登出撤销（按 jti）
     */
    public boolean isTokenBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_JTI_PREFIX + jti));
    }

    /**
     * 用最大 TTL 拉黑用户（用于注销账号、管理员封禁等无 token 可解析的场景）
     */
    public void blacklistUserWithMaxTtl(Long userId) {
        blacklistUser(userId, TimeUnit.MILLISECONDS.toSeconds(jwtExpirationMs));
    }
}

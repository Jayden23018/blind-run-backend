package com.example.demo.interceptor;

import com.example.demo.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Rate limiting interceptor using Redis
 *
 * 使用 Lua 脚本原子执行 INCR + EXPIRE，避免两条命令之间服务崩溃导致 key 永不过期的问题。
 * Lua 脚本在 Redis 单线程中原子执行，等价于一次 CAS 操作。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    /**
     * 原子限流脚本：INCR 后若为首次（count==1）则立即 EXPIRE，两步原子完成。
     * KEYS[1] = Redis key, ARGV[1] = window seconds
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final int authMaxRequests;
    private final int authWindowSeconds;
    private final int registrationMaxRequests;
    private final int registrationWindowSeconds;
    private final int generalMaxRequests;
    private final int generalWindowSeconds;

    public RateLimitInterceptor(StringRedisTemplate redisTemplate,
                                 boolean enabled,
                                 int authMaxRequests,
                                 int authWindowSeconds,
                                 int registrationMaxRequests,
                                 int registrationWindowSeconds,
                                 int generalMaxRequests,
                                 int generalWindowSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.authMaxRequests = authMaxRequests;
        this.authWindowSeconds = authWindowSeconds;
        this.registrationMaxRequests = registrationMaxRequests;
        this.registrationWindowSeconds = registrationWindowSeconds;
        this.generalMaxRequests = generalMaxRequests;
        this.generalWindowSeconds = generalWindowSeconds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // If rate limiting is disabled, allow all requests
        if (!enabled) {
            return true;
        }

        // Extract client IP
        String clientIp = extractClientIp(request);

        // Determine bucket and limits based on request URI
        String uri = request.getRequestURI();
        String bucket;
        int limit;
        int windowSeconds;

        if (uri.startsWith("/api/auth/") || uri.startsWith("/api/cs/auth/")) {
            bucket = "auth";
            limit = authMaxRequests;
            windowSeconds = authWindowSeconds;
        } else if (uri.startsWith("/api/volunteer/registration/")) {
            bucket = "registration";
            limit = registrationMaxRequests;
            windowSeconds = registrationWindowSeconds;
        } else {
            bucket = "general";
            limit = generalMaxRequests;
            windowSeconds = generalWindowSeconds;
        }

        // Redis key for this IP and bucket
        String redisKey = "rate_limit:" + clientIp + ":" + bucket;

        // 原子执行 INCR + EXPIRE（Lua 脚本保证两步不可分割）
        Long count = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(redisKey),
                String.valueOf(windowSeconds)
        );

        // Check if limit exceeded
        if (count != null && count > limit) {
            throw new RateLimitException(windowSeconds);
        }

        return true;
    }

    /**
     * Extract client IP from X-Forwarded-For header or remote address
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
            // Take the first one (original client)
            String[] ips = xForwardedFor.split(",");
            return ips[0].trim();
        }
        return request.getRemoteAddr();
    }
}

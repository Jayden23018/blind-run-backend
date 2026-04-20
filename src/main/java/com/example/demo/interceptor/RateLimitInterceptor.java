package com.example.demo.interceptor;

import com.example.demo.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Rate limiting interceptor using Redis
 */
public class RateLimitInterceptor implements HandlerInterceptor {

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

        // Increment counter and set expiry if it's the first request
        Long count = redisTemplate.execute(new SessionCallback<Long>() {
            @Override
            public Long execute(org.springframework.data.redis.core.RedisOperations operations) {
                Long newCount = operations.opsForValue().increment(redisKey);

                // Set expiry on first request
                if (newCount != null && newCount == 1) {
                    operations.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
                }

                return newCount;
            }
        });

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

package com.example.demo.config;

import com.example.demo.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Rate limiting configuration
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(
            StringRedisTemplate redisTemplate,
            @Value("${rate-limit.enabled}") boolean enabled,
            @Value("${rate-limit.auth.max-requests}") int authMaxRequests,
            @Value("${rate-limit.auth.window-seconds}") int authWindowSeconds,
            @Value("${rate-limit.registration.max-requests}") int registrationMaxRequests,
            @Value("${rate-limit.registration.window-seconds}") int registrationWindowSeconds,
            @Value("${rate-limit.general.max-requests}") int generalMaxRequests,
            @Value("${rate-limit.general.window-seconds}") int generalWindowSeconds
    ) {
        return new RateLimitInterceptor(
                redisTemplate, enabled,
                authMaxRequests, authWindowSeconds,
                registrationMaxRequests, registrationWindowSeconds,
                generalMaxRequests, generalWindowSeconds
        );
    }
}

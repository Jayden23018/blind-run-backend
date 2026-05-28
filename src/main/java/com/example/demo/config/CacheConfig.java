package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Spring Cache → Redis 缓存配置
 *
 * 问题背景（C4）：默认 @EnableCaching + Redis 自动配置不设置 TTL，
 * 若 @CacheEvict 调用失败，模板缓存永远不更新（管理员改文案无法生效）。
 *
 * 修复：为每个缓存指定 TTL，作为 @CacheEvict 主动失效的兜底机制。
 */
@Configuration
public class CacheConfig {

    /**
     * 各缓存 TTL 常量（分钟）
     *
     * notificationTemplates：30 分钟。管理员修改模板后 @CacheEvict 立即清除；
     * 即使 @CacheEvict 失败，最多 30 分钟后自动过期，保证最终一致性。
     */
    private static final Duration TEMPLATE_CACHE_TTL = Duration.ofMinutes(30);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        // 全局默认配置：JSON 序列化 + 不缓存 null（避免 null 值长期占用缓存槽位）
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        // 各缓存独立 TTL
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                // 通知模板缓存：30 分钟 TTL，覆盖默认 60 分钟
                "notificationTemplates", defaultConfig.entryTtl(TEMPLATE_CACHE_TTL)
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}

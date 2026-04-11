package com.example.demo.service;

import com.example.demo.dto.BlindLocationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 盲人位置服务 —— 类似 VolunteerLocationService，双写 Redis + MySQL
 * 当前阶段仅写 Redis（供邻近感知使用）
 */
@Slf4j
@Service
public class BlindLocationService {

    private final StringRedisTemplate redisTemplate;

    /** Redis key 前缀 */
    private static final String REDIS_KEY_PREFIX = "blind:loc:";

    /** 位置 TTL（秒） */
    private static final long LOCATION_TTL_SECONDS = 30;

    public BlindLocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 更新盲人位置
     */
    public void updateLocation(Long userId, BlindLocationRequest request) {
        String key = REDIS_KEY_PREFIX + userId;
        String value = request.getLatitude() + "," + request.getLongitude();
        redisTemplate.opsForValue().set(key, value, LOCATION_TTL_SECONDS, TimeUnit.SECONDS);

        log.debug("盲人位置已更新: userId={}, lat={}, lng={}", userId, request.getLatitude(), request.getLongitude());
    }

    /**
     * 获取盲人位置
     *
     * @return Map with "lat", "lng" keys, or null if not found
     */
    public Map<String, Double> getLocation(Long userId) {
        String key = REDIS_KEY_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        Map<String, Double> location = new HashMap<>();
        location.put("lat", Double.parseDouble(parts[0]));
        location.put("lng", Double.parseDouble(parts[1]));
        return location;
    }
}

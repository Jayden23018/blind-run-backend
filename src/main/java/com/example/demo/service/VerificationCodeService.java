package com.example.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 验证码管理服务 —— 基于 Redis 存储验证码
 *
 * Key: sms:code:{phone}
 * Value: JSON { "code": "123456", "attempts": 0 }
 * TTL: 5 分钟
 */
@Slf4j
@Service
public class VerificationCodeService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sms.code.length:6}")
    private int codeLength;

    @Value("${sms.code.ttl-minutes:5}")
    private int ttlMinutes;

    @Value("${sms.code.max-attempts:5}")
    private int maxAttempts;

    /** 测试号码白名单 —— 这些号码验证码固定为 000000，不发真实短信 */
    @Value("${sms.test-phones:}")
    private List<String> testPhones;

    public VerificationCodeService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 验证码信息 */
    private record CodeEntry(String code, int attempts) {}

    /** 是否为测试号码（验证码固定 000000，跳过短信发送） */
    public boolean isTestPhone(String phone) {
        return testPhones != null && testPhones.contains(phone);
    }

    /** 生成验证码并存储到 Redis */
    public String generateAndStoreCode(String phone) {
        // 测试号码固定使用 000000，其他号码随机生成
        String code;
        if (isTestPhone(phone)) {
            code = "000000";
            log.info("测试号码 {}，验证码固定为 000000（不发短信）", phone);
        } else {
            int min = (int) Math.pow(10, codeLength - 1);
            int max = (int) Math.pow(10, codeLength);
            code = String.valueOf(ThreadLocalRandom.current().nextInt(min, max));
            log.info("为手机号 {} 生成验证码，有效期 {} 分钟", phone, ttlMinutes);
        }

        try {
            String json = objectMapper.writeValueAsString(new CodeEntry(code, 0));
            redisTemplate.opsForValue().set("sms:code:" + phone, json, ttlMinutes, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("验证码存储失败", e);
        }

        return code;
    }

    /** 校验验证码 */
    public boolean verifyCode(String phone, String inputCode) {
        String key = "sms:code:" + phone;
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            log.warn("手机号 {} 没有验证码记录或已过期", phone);
            return false;
        }

        try {
            CodeEntry entry = objectMapper.readValue(json, CodeEntry.class);

            // 检查尝试次数
            int newAttempts = entry.attempts() + 1;
            if (newAttempts > maxAttempts) {
                redisTemplate.delete(key);
                log.warn("手机号 {} 验证码尝试次数超限", phone);
                return false;
            }

            // 比对验证码
            if (entry.code().equals(inputCode)) {
                redisTemplate.delete(key);
                log.info("手机号 {} 验证码验证成功", phone);
                return true;
            }

            // 更新尝试次数
            String updatedJson = objectMapper.writeValueAsString(new CodeEntry(entry.code(), newAttempts));
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                redisTemplate.opsForValue().set(key, updatedJson, ttl, TimeUnit.SECONDS);
            }

            log.warn("手机号 {} 验证码不匹配，已尝试 {}/{} 次", phone, newAttempts, maxAttempts);
            return false;
        } catch (JsonProcessingException e) {
            log.error("解析验证码数据失败", e);
            return false;
        }
    }
}

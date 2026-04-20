package com.example.demo.service;

import com.example.demo.entity.CSUser;
import com.example.demo.repository.CSUserRepository;
import com.example.demo.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 客服认证服务 —— 处理客服账号密码登录
 */
@Slf4j
@Service
public class CSAuthService {

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 15;

    private final CSUserRepository csUserRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    public CSAuthService(CSUserRepository csUserRepository, JwtUtil jwtUtil,
                         StringRedisTemplate redisTemplate) {
        this.csUserRepository = csUserRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.redisTemplate = redisTemplate;
    }

    /**
     * 客服登录
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @return [token, role]
     * @throws RuntimeException 用户名不存在或密码错误时抛出 401
     */
    public String[] login(String username, String password) {
        // 1. 检查账号是否被锁定
        String lockKey = "cs:login:lock:" + username;
        String lockValue = redisTemplate.opsForValue().get(lockKey);
        if (lockValue != null) {
            long remainingSeconds = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
            log.warn("客服账号 {} 已被锁定，剩余 {} 秒", username, remainingSeconds);
            throw new RuntimeException("账号已锁定，请" + Math.max(1, remainingSeconds / 60) + "分钟后再试");
        }

        CSUser csUser = csUserRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        if (!passwordEncoder.matches(password, csUser.getPasswordHash())) {
            // 2. 登录失败，增加失败计数
            String attemptKey = "cs:login:attempts:" + username;
            Long attempts = redisTemplate.opsForValue().increment(attemptKey);
            if (attempts != null && attempts == 1) {
                // 首次失败，设置 15 分钟过期
                redisTemplate.expire(attemptKey, LOCK_DURATION_MINUTES, TimeUnit.MINUTES);
            }

            if (attempts != null && attempts >= MAX_LOGIN_ATTEMPTS) {
                // 3. 超过最大次数，锁定账号
                redisTemplate.opsForValue().set(lockKey, "1", LOCK_DURATION_MINUTES, TimeUnit.MINUTES);
                redisTemplate.delete(attemptKey);
                log.warn("客服账号 {} 连续 {} 次登录失败，已锁定 {} 分钟", username, MAX_LOGIN_ATTEMPTS, LOCK_DURATION_MINUTES);
                throw new RuntimeException("连续登录失败次数过多，账号已锁定" + LOCK_DURATION_MINUTES + "分钟");
            }

            log.warn("客服 {} 登录失败，第 {} 次", username, attempts);
            throw new RuntimeException("用户名或密码错误");
        }

        // 4. 登录成功，清除失败计数
        redisTemplate.delete("cs:login:attempts:" + username);

        // 生成 JWT，携带 csRole claim
        String token = jwtUtil.generateToken(csUser.getId(), csUser.getRole().name());

        log.info("客服 {} (role={}) 登录成功", username, csUser.getRole());

        return new String[]{token, csUser.getRole().name()};
    }

    /**
     * 加密密码（用于初始化数据）
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}

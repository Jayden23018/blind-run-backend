package com.example.demo.service;

import com.example.demo.entity.CSUser;
import com.example.demo.repository.CSUserRepository;
import com.example.demo.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 客服认证服务 —— 处理客服账号密码登录
 */
@Slf4j
@Service
public class CSAuthService {

    private final CSUserRepository csUserRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public CSAuthService(CSUserRepository csUserRepository, JwtUtil jwtUtil) {
        this.csUserRepository = csUserRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
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
        CSUser csUser = csUserRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        if (!passwordEncoder.matches(password, csUser.getPasswordHash())) {
            throw new RuntimeException("用户名或密码错误");
        }

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

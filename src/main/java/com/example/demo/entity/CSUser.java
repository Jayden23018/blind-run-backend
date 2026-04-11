package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服用户实体 —— 对应 cs_users 表
 * 独立于 users 表，使用账号密码登录
 */
@Data
@Entity
@Table(name = "cs_users")
public class CSUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(length = 50)
    private String name;

    @Column(length = 50)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private CSRole role = CSRole.CS;

    @Column(name = "is_online", nullable = false)
    private Boolean isOnline = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * 客服角色枚举
     */
    public enum CSRole {
        CS,      // 普通客服
        ADMIN    // 管理员
    }
}

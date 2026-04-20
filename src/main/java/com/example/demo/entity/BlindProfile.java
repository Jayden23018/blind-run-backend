package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盲人用户资料实体 —— 对应数据库中的 blind_profile 表
 */
@Data
@Entity
@Table(name = "blind_profile")
public class BlindProfile {

    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    private String name;

    @Column(name = "running_pace")
    private String runningPace;

    @Column(name = "special_needs")
    private String specialNeeds;

    /** 身份证姓名 */
    @Column(name = "id_card_name", length = 50)
    private String idCardName;

    /** 身份证号码 */
    @Column(name = "id_card_number", length = 18)
    private String idCardNumber;

    /** 身份认证状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "verify_status", length = 16, nullable = false)
    private BlindVerifyStatus verifyStatus = BlindVerifyStatus.NOT_VERIFIED;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

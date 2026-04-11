package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 紧急事件实体 —— 对应 emergency_events 表
 * 记录盲人触发的紧急求助事件及其处理状态
 */
@Data
@Entity
@Table(name = "emergency_events")
public class EmergencyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 20, nullable = false)
    private TriggerType triggerType = TriggerType.BUTTON;

    @Enumerated(EnumType.STRING)
    @Column(length = 25, nullable = false)
    private EmergencyStatus status = EmergencyStatus.PENDING;

    @Column(name = "gps_lat", precision = 10, scale = 7)
    private BigDecimal gpsLat;

    @Column(name = "gps_lng", precision = 10, scale = 7)
    private BigDecimal gpsLng;

    /** 通知志愿者时间 */
    @Column(name = "volunteer_notified_at")
    private LocalDateTime volunteerNotifiedAt;

    /** 志愿者确认时间 */
    @Column(name = "volunteer_confirmed_at")
    private LocalDateTime volunteerConfirmedAt;

    /** 志愿者响应动作 */
    @Enumerated(EnumType.STRING)
    @Column(name = "volunteer_action", length = 15)
    private VolunteerAction volunteerAction;

    /** 志愿者超时时间（30 秒） */
    @Column(name = "timeout_at")
    private LocalDateTime timeoutAt;

    /** 志愿者响应超时截止时间（定时轮询用） */
    @Column(name = "volunteer_timeout_at")
    private LocalDateTime volunteerTimeoutAt;

    @Column(name = "cs_user_id")
    private Long csUserId;

    @Column(name = "cs_notes", columnDefinition = "TEXT")
    private String csNotes;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (triggeredAt == null) {
            triggeredAt = LocalDateTime.now();
        }
    }
}

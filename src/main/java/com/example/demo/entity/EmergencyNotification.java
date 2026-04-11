package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 紧急通知记录实体 —— 对应 emergency_notifications 表
 * 记录紧急事件中的每一条通知发送情况
 */
@Data
@Entity
@Table(name = "emergency_notifications")
public class EmergencyNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "contact_id")
    private Long contactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_type", length = 20, nullable = false)
    private NotifyType notifyType;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private NotifyStatus status = NotifyStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(columnDefinition = "TEXT")
    private String content;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null && status == NotifyStatus.SENT) {
            sentAt = LocalDateTime.now();
        }
    }
}

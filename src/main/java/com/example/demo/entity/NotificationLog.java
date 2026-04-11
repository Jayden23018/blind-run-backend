package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知发送记录实体 —— 对应 notification_logs 表
 * 记录每条通知的发送状态和内容
 */
@Data
@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 15, nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private NotifyStatus status = NotifyStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "error_msg", length = 200)
    private String errorMsg;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}

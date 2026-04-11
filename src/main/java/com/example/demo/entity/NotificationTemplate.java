package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 通知模板实体 —— 对应 notification_templates 表
 * 管理各事件类型的通知文案模板
 */
@Data
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "order_status", length = 30)
    private String orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", length = 20, nullable = false)
    private TargetRole targetRole;

    @Enumerated(EnumType.STRING)
    @Column(length = 15, nullable = false)
    private NotificationChannel channel;

    @Column(name = "template_text", length = 500, nullable = false)
    private String templateText;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}

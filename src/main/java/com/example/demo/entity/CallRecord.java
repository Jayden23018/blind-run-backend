package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通话记录实体 —— 对应 call_records 表
 * 记录订单中盲人与志愿者之间的通话（通过隐私号或直连）
 */
@Data
@Entity
@Table(name = "call_records")
public class CallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "caller_id", nullable = false)
    private Long callerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "caller_role", length = 15, nullable = false)
    private CallRole callerRole;

    @Column(name = "callee_id", nullable = false)
    private Long calleeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "callee_role", length = 15, nullable = false)
    private CallRole calleeRole;

    @Column(name = "virtual_number", length = 20)
    private String virtualNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 15, nullable = false)
    private CallStatus status = CallStatus.INITIATED;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @PrePersist
    protected void onCreate() {
        if (initiatedAt == null) {
            initiatedAt = LocalDateTime.now();
        }
    }
}

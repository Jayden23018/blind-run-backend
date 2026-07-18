package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 陪跑轨迹点实体 —— 对应 run_order_track_point 表
 *
 * 高频追加写入表，orderId/userId 用普通 Long（不用 @ManyToOne），
 * 参照 RunOrder.dispatchCurrentVolunteerId 同样的先例，避免不必要的关联开销。
 */
@Data
@Entity
@Table(name = "run_order_track_point")
public class RunOrderTrackPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** BLIND 或 VOLUNTEER，复用 UserRole 枚举 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
    }
}

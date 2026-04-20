package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 培训进度实体
 */
@Data
@Entity
@Table(name = "training_progress")
public class TrainingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 志愿者用户ID
     */
    @Column(name = "volunteer_id", nullable = false)
    private Long volunteerId;

    /**
     * 课程ID
     */
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    /**
     * 学习状态
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TrainingProgressStatus status = TrainingProgressStatus.NOT_STARTED;

    /**
     * 进度百分比（0-100）
     */
    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent = 0;

    /**
     * 学习时长（秒）
     */
    @Column(name = "time_spent_seconds", nullable = false)
    private Integer timeSpentSeconds = 0;

    /**
     * 视频播放位置（秒）
     */
    @Column(name = "last_position_seconds", nullable = false)
    private Integer lastPositionSeconds = 0;

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

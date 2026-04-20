package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 培训课程实体
 */
@Data
@Entity
@Table(name = "training_courses")
public class TrainingCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 课程标题
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 课程描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 课程时长（分钟）
     */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    /**
     * 视频URL
     */
    @Column(name = "video_url", length = 500)
    private String videoUrl;

    /**
     * 课程内容（富文本）
     */
    @Column(columnDefinition = "TEXT")
    private String content;

    /**
     * 显示顺序（越小越靠前）
     */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /**
     * 是否激活
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

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

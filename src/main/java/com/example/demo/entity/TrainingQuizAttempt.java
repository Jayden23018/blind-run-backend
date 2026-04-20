package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 培训测验答题记录实体
 */
@Data
@Entity
@Table(name = "training_quiz_attempts")
public class TrainingQuizAttempt {

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
     * 题目ID
     */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /**
     * 用户答案（JSON格式）：["A"] 或 ["A","C"]
     */
    @Column(nullable = false, columnDefinition = "JSON")
    private String userAnswer;

    /**
     * 是否正确
     */
    @Column(nullable = false)
    private Boolean isCorrect;

    /**
     * 答题时长（秒）
     */
    @Column(name = "time_spent_seconds", nullable = false)
    private Integer timeSpentSeconds = 0;

    /**
     * 答题时间
     */
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @PrePersist
    protected void onCreate() {
        attemptedAt = LocalDateTime.now();
    }
}

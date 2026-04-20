package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 培训测验题目实体
 */
@Data
@Entity
@Table(name = "training_quiz_questions")
public class TrainingQuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 课程ID
     */
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    /**
     * 题目文本
     */
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /**
     * 题目类型（单选/多选）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 16)
    private QuestionType questionType;

    /**
     * 选项数组（JSON格式）：["选项A","选项B","选项C","选项D"]
     */
    @Column(nullable = false, columnDefinition = "JSON")
    private String options;

    /**
     * 正确答案数组（JSON格式）：["A"] 或 ["A","C"]
     */
    @Column(nullable = false, columnDefinition = "JSON")
    private String correctAnswer;

    /**
     * 答案解析
     */
    @Column(columnDefinition = "TEXT")
    private String explanation;

    /**
     * 显示顺序
     */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

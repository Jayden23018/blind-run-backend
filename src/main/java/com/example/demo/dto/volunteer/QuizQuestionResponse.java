package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 测验题目响应 DTO
 */
@Data
@AllArgsConstructor
public class QuizQuestionResponse {
    private Long id;
    private Long courseId;
    private String questionText;
    private String questionType; // SINGLE_CHOICE 或 MULTIPLE_CHOICE
    private List<String> options;
    private Integer attemptCount; // 已答题次数
    private Integer displayOrder;
}

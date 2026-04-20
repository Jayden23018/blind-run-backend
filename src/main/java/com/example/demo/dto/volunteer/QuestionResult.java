package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单题答题结果
 */
@Data
@AllArgsConstructor
public class QuestionResult {
    private Long questionId;
    private String questionText;
    private Boolean isCorrect;
    private String explanation;
}

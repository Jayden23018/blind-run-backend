package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 测验结果响应 DTO
 */
@Data
@AllArgsConstructor
public class QuizResultResponse {
    private Boolean passed;
    private Integer correctCount;
    private Integer totalQuestions;
    private Integer scorePercent;
    private List<QuestionResult> questionResults;
    private Integer remainingAttempts; // 剩余答题次数（-1表示无限次）
}

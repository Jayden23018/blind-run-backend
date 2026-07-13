package com.example.demo.dto.volunteer;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测验答案提交请求 DTO
 */
@Data
public class QuizAnswerRequest {

    @NotNull(message = "课程ID不能为空")
    private Long courseId;

    @NotNull(message = "题目ID不能为空")
    private Long questionId;

    @NotEmpty(message = "答案不能为空")
    private List<String> answers; // 完整选项文本原文（非字母码），需与 GET /quiz/{courseId} 返回的 options[] 逐字匹配，如 ["选项A"] 或 ["选项A","选项C"]

    @NotNull(message = "答题时长不能为空")
    private Integer timeSpentSeconds;
}

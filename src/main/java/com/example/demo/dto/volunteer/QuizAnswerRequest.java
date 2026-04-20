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
    private List<String> answers; // ["A"] 或 ["A", "C"]

    @NotNull(message = "答题时长不能为空")
    private Integer timeSpentSeconds;
}

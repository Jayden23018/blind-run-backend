package com.example.demo.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测验题目管理请求 DTO
 */
@Data
public class QuizQuestionRequest {

    @NotNull(message = "课程ID不能为空")
    private Long courseId;

    @NotBlank(message = "题目文本不能为空")
    private String questionText;

    @NotNull(message = "题目类型不能为空")
    private String questionType; // SINGLE_CHOICE 或 MULTIPLE_CHOICE

    @NotEmpty(message = "选项不能为空")
    private List<String> options; // ["选项A","选项B","选项C","选项D"]

    @NotEmpty(message = "正确答案不能为空")
    private List<String> correctAnswer; // ["A"] 或 ["A","C"]

    private String explanation;

    @NotNull(message = "显示顺序不能为空")
    private Integer displayOrder;
}

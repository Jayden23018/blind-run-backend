package com.example.demo.dto.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 培训课程管理请求 DTO
 */
@Data
public class TrainingCourseRequest {

    @NotBlank(message = "课程标题不能为空")
    private String title;

    private String description;

    @NotNull(message = "课程时长不能为空")
    @Min(value = 1, message = "课程时长必须大于0")
    private Integer durationMinutes;

    private String videoUrl;

    private String content;

    @NotNull(message = "显示顺序不能为空")
    @Min(value = 0, message = "显示顺序不能小于0")
    private Integer displayOrder;

    private Boolean isActive = true;
}

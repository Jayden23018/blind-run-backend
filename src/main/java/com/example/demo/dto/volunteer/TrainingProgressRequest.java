package com.example.demo.dto.volunteer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 培训进度提交请求 DTO
 */
@Data
public class TrainingProgressRequest {

    @NotNull(message = "课程ID不能为空")
    private Long courseId;

    @NotNull(message = "进度百分比不能为空")
    @Min(value = 0, message = "进度百分比不能小于0")
    @Max(value = 100, message = "进度百分比不能大于100")
    private Integer progressPercent;

    @NotNull(message = "视频播放位置不能为空")
    @Min(value = 0, message = "视频播放位置不能小于0")
    private Integer lastPositionSeconds;

    @NotNull(message = "学习时长不能为空")
    @Min(value = 0, message = "学习时长不能小于0")
    private Integer timeSpentSeconds;
}

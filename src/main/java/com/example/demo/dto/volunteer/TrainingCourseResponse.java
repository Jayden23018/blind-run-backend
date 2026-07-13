package com.example.demo.dto.volunteer;

import com.example.demo.entity.TrainingProgressStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 培训课程响应 DTO
 */
@Data
@AllArgsConstructor
public class TrainingCourseResponse {
    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;
    private String videoUrl;
    private String content;
    private Integer displayOrder;
    private Boolean isRequired; // 是否必修
    private TrainingProgressStatus progressStatus; // 该用户的进度
    private Integer progressPercent;
    private Boolean isCompleted;
    private Boolean canTakeQuiz; // progressPercent >= 95 才能测验
    private String quizLockedReason; // canTakeQuiz=false 时的原因说明，可直接展示；已解锁时为 null
}

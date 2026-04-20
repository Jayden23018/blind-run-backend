package com.example.demo.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 培训统计数据响应 DTO
 */
@Data
@AllArgsConstructor
public class TrainingStatsResponse {
    private Long totalVolunteers;
    private Long completedTrainingVolunteers;
    private Long inProgressVolunteers;
    private Double completionRate; // 完成率百分比
}

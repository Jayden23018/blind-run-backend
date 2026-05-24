package com.example.demo.dto.admin;

import com.example.demo.entity.TrainingCourse;

import java.time.LocalDateTime;

public record TrainingCourseResponse(
        Long id,
        String title,
        String description,
        Integer durationMinutes,
        String videoUrl,
        String content,
        Integer displayOrder,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TrainingCourseResponse from(TrainingCourse c) {
        return new TrainingCourseResponse(
                c.getId(), c.getTitle(), c.getDescription(),
                c.getDurationMinutes(), c.getVideoUrl(), c.getContent(),
                c.getDisplayOrder(), c.getIsActive(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}

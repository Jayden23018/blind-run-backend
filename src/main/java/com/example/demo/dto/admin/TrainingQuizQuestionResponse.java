package com.example.demo.dto.admin;

import com.example.demo.entity.TrainingQuizQuestion;

import java.time.LocalDateTime;
import java.util.List;

public record TrainingQuizQuestionResponse(
        Long id,
        Long courseId,
        String questionText,
        String questionType,
        List<String> options,
        List<String> correctAnswer,
        String explanation,
        Integer displayOrder,
        LocalDateTime createdAt
) {
    public static TrainingQuizQuestionResponse from(TrainingQuizQuestion q,
                                                     List<String> options,
                                                     List<String> correctAnswer) {
        return new TrainingQuizQuestionResponse(
                q.getId(), q.getCourseId(), q.getQuestionText(),
                q.getQuestionType().name(),
                options, correctAnswer,
                q.getExplanation(), q.getDisplayOrder(), q.getCreatedAt()
        );
    }
}

package com.example.demo.repository;

import com.example.demo.entity.TrainingQuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 培训测验答题记录 Repository
 */
@Repository
public interface TrainingQuizAttemptRepository extends JpaRepository<TrainingQuizAttempt, Long> {

    /**
     * 查询志愿者在指定课程的所有答题记录
     */
    List<TrainingQuizAttempt> findByVolunteerIdAndCourseIdOrderByAttemptedAtDesc(Long volunteerId, Long courseId);

    /**
     * 查询志愿者在指定课程的答题次数（允许重复作答）
     */
    long countByVolunteerIdAndQuestionId(Long volunteerId, Long questionId);

    /**
     * 查询志愿者在指定课程的所有正确答题记录
     */
    List<TrainingQuizAttempt> findByVolunteerIdAndCourseIdAndIsCorrectTrue(Long volunteerId, Long courseId);

    /**
     * 删除志愿者在指定课程的所有答题记录
     */
    void deleteByVolunteerIdAndCourseId(Long volunteerId, Long courseId);
}

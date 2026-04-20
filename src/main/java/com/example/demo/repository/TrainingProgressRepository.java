package com.example.demo.repository;

import com.example.demo.entity.TrainingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 培训进度 Repository
 */
@Repository
public interface TrainingProgressRepository extends JpaRepository<TrainingProgress, Long> {

    /**
     * 查询志愿者在指定课程的进度
     */
    Optional<TrainingProgress> findByVolunteerIdAndCourseId(Long volunteerId, Long courseId);

    /**
     * 查询志愿者的所有学习进度
     */
    List<TrainingProgress> findByVolunteerIdOrderByCourseId(Long volunteerId);

    /**
     * 查询志愿者已完成的课程数
     */
    long countByVolunteerIdAndStatus(Long volunteerId, com.example.demo.entity.TrainingProgressStatus status);
}

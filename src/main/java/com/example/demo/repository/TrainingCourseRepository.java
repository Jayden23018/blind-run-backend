package com.example.demo.repository;

import com.example.demo.entity.TrainingCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 培训课程 Repository
 */
@Repository
public interface TrainingCourseRepository extends JpaRepository<TrainingCourse, Long> {

    /**
     * 查询所有激活的课程，按显示顺序排序
     */
    List<TrainingCourse> findByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * 根据ID查询激活的课程
     */
    Optional<TrainingCourse> findByIdAndIsActiveTrue(Long id);

    /**
     * 根据显示顺序查询课程
     */
    Optional<TrainingCourse> findByDisplayOrder(Integer displayOrder);
}

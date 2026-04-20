package com.example.demo.repository;

import com.example.demo.entity.TrainingQuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 培训测验题目 Repository
 */
@Repository
public interface TrainingQuizQuestionRepository extends JpaRepository<TrainingQuizQuestion, Long> {

    /**
     * 查询指定课程的所有题目，按显示顺序排序
     */
    List<TrainingQuizQuestion> findByCourseIdOrderByDisplayOrderAsc(Long courseId);

    /**
     * 统计指定课程的题目数量
     */
    long countByCourseId(Long courseId);
}

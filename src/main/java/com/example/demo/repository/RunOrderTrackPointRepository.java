package com.example.demo.repository;

import com.example.demo.entity.RunOrderTrackPoint;
import com.example.demo.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RunOrderTrackPointRepository extends JpaRepository<RunOrderTrackPoint, Long> {

    List<RunOrderTrackPoint> findByOrderIdAndRoleOrderByRecordedAtAsc(Long orderId, UserRole role);

    /** 留存期清理：删除早于 cutoff 的轨迹点，见 TrackDataRetentionScheduler */
    void deleteByRecordedAtBefore(LocalDateTime cutoff);

    /** 账号注销级联清理：删除该用户名下所有轨迹点（含盲人/志愿者两种角色产生的记录） */
    void deleteByUserId(Long userId);
}

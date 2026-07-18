package com.example.demo.repository;

import com.example.demo.entity.RunOrderTrackPoint;
import com.example.demo.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunOrderTrackPointRepository extends JpaRepository<RunOrderTrackPoint, Long> {

    List<RunOrderTrackPoint> findByOrderIdAndRoleOrderByRecordedAtAsc(Long orderId, UserRole role);
}

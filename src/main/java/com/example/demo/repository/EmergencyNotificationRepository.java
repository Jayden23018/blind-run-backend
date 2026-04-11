package com.example.demo.repository;

import com.example.demo.entity.EmergencyNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 紧急通知记录数据访问层
 */
@Repository
public interface EmergencyNotificationRepository extends JpaRepository<EmergencyNotification, Long> {

    /** 查询某事件的所有通知 */
    List<EmergencyNotification> findByEventId(Long eventId);
}

package com.example.demo.repository;

import com.example.demo.entity.EmergencyEvent;
import com.example.demo.entity.EmergencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 紧急事件数据访问层
 */
@Repository
public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long> {

    /** 查询指定状态的事件（客服端） */
    List<EmergencyEvent> findByStatusIn(List<EmergencyStatus> statuses);

    /** 查询某订单的紧急事件 */
    List<EmergencyEvent> findByOrderId(Long orderId);

    /** 查询某用户的紧急事件 */
    List<EmergencyEvent> findByUserId(Long userId);

    /** 查询志愿者超时未响应的事件（定时轮询用） */
    List<EmergencyEvent> findByStatusAndVolunteerTimeoutAtBefore(EmergencyStatus status, LocalDateTime now);
}

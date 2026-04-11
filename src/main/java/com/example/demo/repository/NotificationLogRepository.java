package com.example.demo.repository;

import com.example.demo.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通知发送记录数据访问层
 */
@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /** 查询某订单的所有通知 */
    List<NotificationLog> findByOrderIdOrderBySentAtDesc(Long orderId);

    /** 查询某用户的所有通知 */
    List<NotificationLog> findByUserIdOrderBySentAtDesc(Long userId);
}

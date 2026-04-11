package com.example.demo.repository;

import com.example.demo.entity.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 订单状态日志数据访问层
 */
@Repository
public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, Long> {

    /** 查询某订单的状态变更历史 */
    List<OrderStatusLog> findByOrderIdOrderByChangedAtDesc(Long orderId);
}

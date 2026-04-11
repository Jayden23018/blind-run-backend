package com.example.demo.service;

import com.example.demo.entity.OrderStatusLog;
import com.example.demo.repository.OrderStatusLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单状态日志服务 —— 记录每次状态变更
 */
@Slf4j
@Service
public class OrderStatusLogService {

    private final OrderStatusLogRepository statusLogRepository;

    public OrderStatusLogService(OrderStatusLogRepository statusLogRepository) {
        this.statusLogRepository = statusLogRepository;
    }

    /**
     * 记录状态变更
     */
    @Transactional
    public void logStatusChange(Long orderId, String fromStatus, String toStatus, Long changedBy, String remark) {
        OrderStatusLog statusLog = new OrderStatusLog();
        statusLog.setOrderId(orderId);
        statusLog.setFromStatus(fromStatus);
        statusLog.setToStatus(toStatus);
        statusLog.setChangedBy(changedBy);
        statusLog.setRemark(remark);
        statusLogRepository.save(statusLog);

        log.info("订单 {} 状态变更: {} → {}, 操作人={}, 备注={}",
                orderId, fromStatus, toStatus, changedBy, remark);
    }

    /**
     * 查询订单的状态变更历史
     */
    public List<OrderStatusLog> getStatusLogs(Long orderId) {
        return statusLogRepository.findByOrderIdOrderByChangedAtDesc(orderId);
    }
}

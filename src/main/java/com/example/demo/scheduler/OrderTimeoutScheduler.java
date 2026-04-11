package com.example.demo.scheduler;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时定时任务 —— 每 60 秒检查一次，将超过计划结束时间的 IN_PROGRESS 订单自动完成
 */
@Slf4j
@Component
public class OrderTimeoutScheduler {

    private final RunOrderRepository runOrderRepository;
    private final com.example.demo.service.OrderStatusLogService statusLogService;
    private final OrderService orderService;

    public OrderTimeoutScheduler(RunOrderRepository runOrderRepository,
                                  com.example.demo.service.OrderStatusLogService statusLogService,
                                  OrderService orderService) {
        this.runOrderRepository = runOrderRepository;
        this.statusLogService = statusLogService;
        this.orderService = orderService;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void autoCompleteTimedOutOrders() {
        List<RunOrder> timedOut = runOrderRepository.findTimedOutOrders(LocalDateTime.now());
        for (RunOrder order : timedOut) {
            String oldStatus = order.getStatus().name();
            order.setStatus(OrderStatus.COMPLETED);
            order.setFinishedAt(LocalDateTime.now());
            statusLogService.logStatusChange(order.getId(), oldStatus, "COMPLETED", null, "超时自动完成");
            log.info("订单 {} 超过计划结束时间，系统自动完成", order.getId());
        }
        if (!timedOut.isEmpty()) {
            runOrderRepository.saveAll(timedOut);
        }
    }
}

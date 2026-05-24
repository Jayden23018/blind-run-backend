package com.example.demo.scheduler;

import com.example.demo.entity.EmergencyEvent;
import com.example.demo.entity.EmergencyStatus;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.repository.EmergencyEventRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.EmergencyService;
import com.example.demo.service.OrderLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时轮询调度器 —— 替代 ScheduledExecutorService 和 Redis key 超时机制
 *
 * 两个轮询任务：
 * 1. 紧急事件志愿者超时：每 10 秒扫描 status=VOLUNTEER_NOTIFIED 且 volunteer_timeout_at < NOW() 的事件
 * 2. 重新匹配超时提醒：每 10 秒扫描 status=REMATCHING 且 rematch_notify_at < NOW() 的订单
 */
@Slf4j
@Component
public class TimeoutScheduler {

    private final EmergencyEventRepository eventRepository;
    private final RunOrderRepository runOrderRepository;
    private final EmergencyService emergencyService;
    private final OrderLifecycleService orderLifecycleService;

    public TimeoutScheduler(EmergencyEventRepository eventRepository,
                             RunOrderRepository runOrderRepository,
                             EmergencyService emergencyService,
                             OrderLifecycleService orderLifecycleService) {
        this.eventRepository = eventRepository;
        this.runOrderRepository = runOrderRepository;
        this.emergencyService = emergencyService;
        this.orderLifecycleService = orderLifecycleService;
    }

    /**
     * 检查紧急事件志愿者超时 —— 每 10 秒扫描
     */
    @Scheduled(fixedDelay = 10000)
    public void checkEmergencyTimeout() {
        List<EmergencyEvent> timedOut = eventRepository
                .findByStatusAndVolunteerTimeoutAtBefore(
                        EmergencyStatus.VOLUNTEER_NOTIFIED, LocalDateTime.now());

        for (EmergencyEvent event : timedOut) {
            try {
                emergencyService.handleVolunteerTimeout(event.getId());
            } catch (Exception e) {
                log.warn("处理紧急事件 {} 志愿者超时失败: {}", event.getId(), e.getMessage());
            }
        }
    }

    /**
     * 检查重新匹配超时 —— 每 10 秒扫描
     */
    @Scheduled(fixedDelay = 10000)
    public void checkRematchTimeout() {
        List<RunOrder> orders = runOrderRepository
                .findByStatusAndRematchNotifyAtBefore(
                        OrderStatus.REMATCHING, LocalDateTime.now());

        for (RunOrder order : orders) {
            try {
                orderLifecycleService.handleRematchTimeout(order.getId());
            } catch (Exception e) {
                log.warn("处理订单 {} 重新匹配超时失败: {}", order.getId(), e.getMessage());
            }
        }
    }

    /**
     * 检查匹配超时 —— 每 10 秒扫描 PENDING_MATCH 且 matchNotifyAt 已过的订单
     */
    @Scheduled(fixedDelay = 10000)
    public void checkMatchTimeout() {
        List<RunOrder> orders = runOrderRepository
                .findByStatusAndMatchNotifyAtBefore(
                        OrderStatus.PENDING_MATCH, LocalDateTime.now());

        for (RunOrder order : orders) {
            // 跳过由 DispatchScheduler 管理的派单订单
            if (order.getDispatchStartedAt() != null) continue;
            try {
                orderLifecycleService.handleMatchTimeout(order.getId());
            } catch (Exception e) {
                log.warn("处理订单 {} 匹配超时失败: {}", order.getId(), e.getMessage());
            }
        }
    }

    /**
     * 检查超时挂起订单 —— 每 60 秒扫描超过结束时间1小时且未通知的进行中订单
     */
    @Scheduled(fixedDelay = 60000)
    public void checkOverdueOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(1);
        List<RunOrder> orders = runOrderRepository
                .findByStatusAndPlannedEndTimeBeforeAndOverdueNotifiedFalse(
                        OrderStatus.IN_PROGRESS, threshold);

        for (RunOrder order : orders) {
            try {
                orderLifecycleService.handleOverdueOrder(order.getId());
            } catch (Exception e) {
                log.warn("处理订单 {} 超时挂起失败: {}", order.getId(), e.getMessage());
            }
        }
    }
}

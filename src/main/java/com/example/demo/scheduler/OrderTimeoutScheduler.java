package com.example.demo.scheduler;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.TargetRole;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.NotificationService;
import com.example.demo.service.ProximityService;
import com.example.demo.service.SchedulerLockService;
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
    private final NotificationService notificationService;
    private final ProximityService proximityService;
    private final SchedulerLockService schedulerLockService;

    public OrderTimeoutScheduler(RunOrderRepository runOrderRepository,
                                  com.example.demo.service.OrderStatusLogService statusLogService,
                                  NotificationService notificationService,
                                  ProximityService proximityService,
                                  SchedulerLockService schedulerLockService) {
        this.runOrderRepository = runOrderRepository;
        this.statusLogService = statusLogService;
        this.notificationService = notificationService;
        this.proximityService = proximityService;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void autoCompleteTimedOutOrders() {
        if (!schedulerLockService.tryLock("autoComplete", 120)) return;
        // BUG修复：try 块必须紧接在 tryLock 之后，确保任何异常（DB查询失败、通知失败等）
        // 都能触发 finally 中的 releaseLock，防止 Watchdog 无限续期导致锁永久占用
        try {
            List<RunOrder> timedOut = runOrderRepository.findTimedOutOrders(LocalDateTime.now());
            List<RunOrder> toSave = new java.util.ArrayList<>();
            for (RunOrder order : timedOut) {
                // 重新加载，确保关联实体（blindUser、volunteer）已通过 JOIN FETCH 加载
                RunOrder fullOrder = runOrderRepository.findByIdWithUsers(order.getId()).orElse(null);
                if (fullOrder == null) continue;

                String oldStatus = fullOrder.getStatus().name();
                fullOrder.setStatus(OrderStatus.COMPLETED);
                fullOrder.setFinishedAt(LocalDateTime.now());
                statusLogService.logStatusChange(fullOrder.getId(), oldStatus, "COMPLETED", null, "超时自动完成");

                // 清除邻近感知标记
                proximityService.clearProximityFlag(fullOrder.getId());

                // 通知盲人用户
                if (fullOrder.getBlindUser() != null) {
                    notificationService.sendNotification(
                            fullOrder.getBlindUser().getId(), "ORDER_COMPLETED", TargetRole.BLIND_USER, null);
                }
                // 通知志愿者
                if (fullOrder.getVolunteer() != null) {
                    notificationService.sendNotification(
                            fullOrder.getVolunteer().getId(), "ORDER_COMPLETED", TargetRole.VOLUNTEER, null);
                }

                toSave.add(fullOrder);
                log.info("订单 {} 超过计划结束时间，系统自动完成", fullOrder.getId());
            }
            // BUG修复：移除重复的 saveAll(timedOut)。
            // 同一 Hibernate Session 内，fullOrder 与 timedOut 中的 order 是同一对象引用，
            // saveAll(toSave) 已经持久化了所有修改，再次 saveAll(timedOut) 是无效的重复 UPDATE。
            if (!toSave.isEmpty()) {
                runOrderRepository.saveAll(toSave);
            }
        } finally {
            schedulerLockService.releaseLock("autoComplete");
        }
    }
}

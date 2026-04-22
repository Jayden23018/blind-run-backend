package com.example.demo.scheduler;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.DispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 派单定时任务 —— 每 5 秒轮询，驱动串行派单流程
 *
 * 职责：
 * 1. 检测 dispatch_current 超时（Redis key 过期但 DB 字段不为空）→ 通知下一个志愿者
 * 2. 崩溃恢复：DB 有 dispatchStartedAt 但 Redis 无状态 → 重新派单
 */
@Slf4j
@Component
public class DispatchScheduler {

    private final RunOrderRepository runOrderRepository;
    private final DispatchService dispatchService;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.dispatch.per-volunteer-timeout-seconds:30}")
    private int perVolunteerTimeoutSeconds;

    public DispatchScheduler(RunOrderRepository runOrderRepository,
                             DispatchService dispatchService,
                             StringRedisTemplate redisTemplate) {
        this.runOrderRepository = runOrderRepository;
        this.dispatchService = dispatchService;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.scheduler-poll-interval-ms:5000}")
    public void processDispatchQueue() {
        List<RunOrder> activeOrders = runOrderRepository
                .findByStatusAndDispatchStartedAtNotNull(OrderStatus.PENDING_MATCH);

        for (RunOrder order : activeOrders) {
            try {
                String currentKey = String.format(DispatchService.CURRENT_KEY, order.getId());
                String currentVol = redisTemplate.opsForValue().get(currentKey);

                if (currentVol == null && order.getDispatchCurrentVolunteerId() != null) {
                    // 情况1：Redis key 过期（30s TTL 到了），DB 还记录着志愿者 ID → 超时
                    log.info("订单 {} 志愿者 {} 响应超时，派送给下一个", order.getId(), order.getDispatchCurrentVolunteerId());
                    dispatchService.handleVolunteerTimeout(order.getId());
                } else if (currentVol == null && order.getDispatchCurrentVolunteerId() == null) {
                    // 情况2：崩溃恢复 — DB 有 dispatchStartedAt 但无 Redis 状态
                    // 检查队列是否还有数据
                    String queueKey = String.format(DispatchService.QUEUE_KEY, order.getId());
                    Long queueSize = redisTemplate.opsForList().size(queueKey);
                    if (queueSize != null && queueSize > 0) {
                        log.info("订单 {} 崩溃恢复：重新开始派单", order.getId());
                        dispatchService.dispatchToNext(order);
                    }
                    // 队列也为空的话由 tryExpandRound 处理
                }
                // else: 当前志愿者还在 30s 窗口内，不操作
            } catch (Exception e) {
                log.warn("DispatchScheduler 处理订单 {} 异常: {}", order.getId(), e.getMessage());
            }
        }
    }
}

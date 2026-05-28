package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.repository.RunOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 启动诊断（C5）
 *
 * 问题背景：Redis 默认不开启 RDB/AOF 持久化，服务器重启后所有 Redis 数据丢失。
 * 但数据库中可能仍有"派单进行中"的订单，其对应的 Redis 队列已消失。
 *
 * 应用层能做的事：
 * 1. 检测"数据库有派单状态、Redis 无队列"的订单并输出告警日志
 * 2. DispatchScheduler 的崩溃恢复逻辑（dispatchToNext）会在下一个轮询（5s）内自动重建队列
 * 3. 真正的持久化保障需在 Redis 服务端配置 RDB 或 AOF（见下方注释）
 *
 * Redis 服务端持久化配置（需在 redis.conf 或 Redis 启动命令中设置，应用层无法控制）：
 *   appendonly yes          # 启用 AOF（推荐，数据丢失最少）
 *   appendfsync everysec    # AOF 每秒 fsync，性能与安全平衡
 *   save 900 1              # RDB：900秒内有 1 次写操作则快照
 *   save 300 10             # RDB：300秒内有 10 次写操作则快照
 *   save 60 10000           # RDB：60秒内有 10000 次写操作则快照
 * ECS 生产环境建议：AOF + RDB 双重保障，同时配置 Redis Sentinel（见 application.properties）
 */
@Slf4j
@Service
public class RedisHealthCheck {

    private static final String DISPATCH_QUEUE_PREFIX = "order:dispatch:queue:";
    private static final String DISPATCH_CURRENT_PREFIX = "order:dispatch:current:";

    private final RunOrderRepository runOrderRepository;
    private final StringRedisTemplate redisTemplate;

    public RedisHealthCheck(RunOrderRepository runOrderRepository,
                             StringRedisTemplate redisTemplate) {
        this.runOrderRepository = runOrderRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 应用启动后检测 Redis 与数据库的状态一致性
     *
     * 若 Redis 数据全部丢失（重启/清空），派单中的订单会被 DispatchScheduler 在 5s 内自动恢复。
     * 本方法只做诊断日志，不做修复（修复由 DispatchScheduler 承担）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkRedisConsistency() {
        try {
            // 查找数据库中仍在派单流程中的订单
            List<com.example.demo.entity.RunOrder> dispatchingOrders =
                    runOrderRepository.findByStatusAndDispatchStartedAtNotNull(OrderStatus.PENDING_MATCH);

            if (dispatchingOrders.isEmpty()) {
                log.info("[Redis诊断] 无进行中的派单订单，Redis 状态与数据库一致");
                return;
            }

            int lostQueueCount = 0;
            for (com.example.demo.entity.RunOrder order : dispatchingOrders) {
                String queueKey = DISPATCH_QUEUE_PREFIX + order.getId();
                String currentKey = DISPATCH_CURRENT_PREFIX + order.getId();
                Long queueLen = redisTemplate.opsForList().size(queueKey);
                String currentVol = redisTemplate.opsForValue().get(currentKey);

                boolean queueLost = (queueLen == null || queueLen == 0) && currentVol == null;
                if (queueLost) {
                    lostQueueCount++;
                    log.warn("[Redis诊断] 订单 {} 的派单队列已丢失（可能因 Redis 重启），" +
                             "DispatchScheduler 将在 5s 内自动恢复", order.getId());
                }
            }

            if (lostQueueCount == 0) {
                log.info("[Redis诊断] {} 个派单订单，Redis 队列状态正常", dispatchingOrders.size());
            } else {
                log.warn("[Redis诊断] {} / {} 个派单订单的 Redis 队列已丢失，" +
                         "DispatchScheduler 崩溃恢复将在首次轮询时自动介入。" +
                         "建议在 Redis 服务端启用 AOF 持久化（appendonly yes）以避免此问题。",
                         lostQueueCount, dispatchingOrders.size());
            }
        } catch (Exception e) {
            log.warn("[Redis诊断] 启动一致性检查失败（不影响启动）: {}", e.getMessage());
        }
    }
}

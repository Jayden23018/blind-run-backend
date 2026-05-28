package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分布式调度锁 —— 防止多实例部署时调度任务重复执行
 *
 * 核心机制：
 * 1. SETNX 加锁（含 TTL 兜底）
 * 2. Watchdog 续期：任务运行期间每 TTL/3 秒自动续期，消除"任务执行超过 TTL 导致并发执行"的问题
 * 3. Lua 脚本原子释放：校验持有者再删，防止误删他人的锁
 */
@Slf4j
@Service
public class SchedulerLockService {

    private static final String LOCK_PREFIX = "scheduler:lock:";

    // 原子释放脚本：持有者匹配才删
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class
    );

    // 续期脚本：持有者匹配才重置 TTL（单位：秒）
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('expire', KEYS[1], ARGV[2]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    // 本实例唯一标识，用于区分锁的持有者
    private final String instanceId = UUID.randomUUID().toString();

    // Watchdog 线程池：daemon 线程，不阻塞 JVM 关闭
    private final ScheduledExecutorService watchdogExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "lock-watchdog");
        t.setDaemon(true);
        return t;
    });

    // lockName → 正在运行的 watchdog 任务
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watchdogs = new ConcurrentHashMap<>();

    public SchedulerLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("SchedulerLockService 初始化，实例 ID: {}", instanceId);
    }

    /**
     * 尝试获取调度锁，成功后自动启动 Watchdog 定期续期
     *
     * @param lockName   锁名称（如 "dispatchQueue"）
     * @param ttlSeconds 初始 TTL（秒）；Watchdog 会在任务运行期间自动续期，TTL 仅作为崩溃兜底
     * @return true 表示获取成功，调用方应执行任务并在 finally 中调用 releaseLock
     */
    public boolean tryLock(String lockName, long ttlSeconds) {
        String key = LOCK_PREFIX + lockName;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, instanceId, ttlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("调度锁 [{}] 已被其他实例持有，跳过本次执行", lockName);
            return false;
        }
        startWatchdog(lockName, key, ttlSeconds);
        return true;
    }

    /**
     * 释放调度锁：先停 Watchdog，再原子删除 Redis key
     */
    public void releaseLock(String lockName) {
        cancelWatchdog(lockName);
        String key = LOCK_PREFIX + lockName;
        try {
            Long result = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), instanceId);
            if (result == null || result == 0) {
                log.debug("调度锁 [{}] 释放时已不属于本实例（可能已超时被其他实例接管）", lockName);
            }
        } catch (Exception e) {
            log.warn("释放调度锁 [{}] 失败: {}", lockName, e.getMessage());
        }
    }

    // ── 私有方法 ───────────────────────────────────────────────────────────────

    /**
     * 启动 Watchdog：每 ttl/3 秒续期一次
     *
     * 为什么是 ttl/3：
     * - 网络延迟或 GC 可能导致续期请求比预期晚几百毫秒
     * - ttl/3 确保在锁过期前至少有 2 次续期机会，大幅降低意外过期概率
     */
    private void startWatchdog(String lockName, String key, long ttlSeconds) {
        long intervalSeconds = Math.max(1, ttlSeconds / 3);
        ScheduledFuture<?> future = watchdogExecutor.scheduleAtFixedRate(
                () -> renewLock(key, lockName, ttlSeconds),
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        );
        watchdogs.put(lockName, future);
        log.debug("调度锁 [{}] Watchdog 已启动，续期间隔 {}s", lockName, intervalSeconds);
    }

    private void renewLock(String key, String lockName, long ttlSeconds) {
        try {
            Long result = redisTemplate.execute(
                    RENEW_SCRIPT, List.of(key), instanceId, String.valueOf(ttlSeconds));
            if (result == null || result == 0) {
                // 锁已被其他实例接管（本实例某次续期失败且 TTL 到期），停止 Watchdog 避免干扰
                log.warn("调度锁 [{}] 续期失败，锁已不属于本实例，停止 Watchdog", lockName);
                cancelWatchdog(lockName);
            } else {
                log.debug("调度锁 [{}] 续期成功，TTL 重置为 {}s", lockName, ttlSeconds);
            }
        } catch (Exception e) {
            log.warn("调度锁 [{}] 续期异常: {}", lockName, e.getMessage());
        }
    }

    private void cancelWatchdog(String lockName) {
        ScheduledFuture<?> future = watchdogs.remove(lockName);
        if (future != null) {
            future.cancel(false); // false: 不中断正在执行的续期，等它自然结束
            log.debug("调度锁 [{}] Watchdog 已停止", lockName);
        }
    }
}

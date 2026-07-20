package com.example.demo.scheduler;

import com.example.demo.websocket.UnifiedSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 死连接检测 —— 定期关闭超过阈值无活跃的本机连接。
 *
 * 只处理本机 session（UnifiedSessionRegistry.userSessions 本身就是本机会话表，
 * 多实例部署靠 Redis Pub/Sub 转发），无需分布式锁：各实例独立清理自己的连接，不会重复处理。
 */
@Slf4j
@Component
public class WebSocketHeartbeatScheduler {

    private final UnifiedSessionRegistry sessionRegistry;

    @Value("${app.websocket.dead-connection-timeout-seconds:90}")
    private long deadConnectionTimeoutSeconds;

    public WebSocketHeartbeatScheduler(UnifiedSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Scheduled(fixedRateString = "${app.websocket.dead-connection-check-interval-ms:30000}")
    public void checkDeadConnections() {
        sessionRegistry.closeStaleSessions(deadConnectionTimeoutSeconds * 1000);
    }
}

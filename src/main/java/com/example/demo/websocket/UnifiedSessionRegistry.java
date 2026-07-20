package com.example.demo.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import org.springframework.web.socket.CloseStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 WebSocket 会话注册表 —— 管理本机所有用户的 WebSocket 连接
 *
 * 多实例部署时：
 * - sendToUser()：先查本机 session；找不到则通过 WebSocketMessageBroker 发布到 Redis，
 *   由持有该 session 的实例负责最终投递
 * - sendToCs()：始终通过 Redis 广播，所有实例各自投递给本机 CS session
 * - sendToLocalUser() / sendToLocalCs()：仅操作本机 session（供 Redis 订阅回调调用）
 */
@Slf4j
@Component
public class UnifiedSessionRegistry {

    private final ConcurrentHashMap<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WebSocketSession> csSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastActivityTime = new ConcurrentHashMap<>();

    // @Lazy：WebSocketMessageBroker 也依赖本类，@Lazy 延迟注入打破循环依赖
    private final WebSocketMessageBroker messageBroker;

    public UnifiedSessionRegistry(@Lazy WebSocketMessageBroker messageBroker) {
        this.messageBroker = messageBroker;
    }

    public void register(Long userId, WebSocketSession session, String role) {
        WebSocketSession oldSession = userSessions.put(userId, session);
        if (oldSession != null && oldSession.isOpen()) {
            try { oldSession.close(); } catch (IOException e) {
                log.warn("关闭用户 {} 旧 WebSocket 连接失败", userId);
            }
        }
        if ("CS".equals(role)) {
            csSessions.put(userId, session);
        }
        lastActivityTime.put(userId, System.currentTimeMillis());
        log.info("用户 {} (role={}) WebSocket 已连接，本机在线: {}", userId, role, userSessions.size());
    }

    public void register(Long userId, WebSocketSession session) {
        register(userId, session, null);
    }

    public void unregister(Long userId) {
        userSessions.remove(userId);
        csSessions.remove(userId);
        lastActivityTime.remove(userId);
        log.info("用户 {} WebSocket 已断开，本机在线: {}", userId, userSessions.size());
    }

    public Optional<WebSocketSession> getSession(Long userId) {
        return Optional.ofNullable(userSessions.get(userId));
    }

    /** 更新用户最后活跃时间，收到任意合法消息（不限于心跳）即算存活信号 */
    public void touch(Long userId) {
        lastActivityTime.put(userId, System.currentTimeMillis());
    }

    /**
     * 关闭超过 timeoutMs 未活跃的本机 session（网络异常中断、无正常 FIN/CLOSE 的死连接）
     *
     * 不在此处手动 unregister —— session.close() 会触发 WS 处理器已有的
     * afterConnectionClosed 回调，由它完成注册表清理 + 角色相关清理（位置下线等），避免重复逻辑。
     */
    public void closeStaleSessions(long timeoutMs) {
        long now = System.currentTimeMillis();
        for (var entry : userSessions.entrySet()) {
            Long userId = entry.getKey();
            Long last = lastActivityTime.get(userId);
            if (last == null || now - last >= timeoutMs) {
                try {
                    entry.getValue().close(CloseStatus.SESSION_NOT_RELIABLE);
                    log.info("用户 {} WebSocket 超过 {}ms 无活跃，服务端主动关闭", userId, timeoutMs);
                } catch (IOException e) {
                    log.warn("关闭用户 {} 死连接失败: {}", userId, e.getMessage());
                }
            }
        }
    }

    /**
     * 向指定用户发送消息（支持跨实例）
     *
     * 本机有 session → 直接发送；
     * 本机无 session → 发布到 Redis，其他实例处理。
     */
    public void sendToUser(Long userId, String message) {
        if (sendToLocalUser(userId, message)) return;
        // 本机未找到，转发给其他实例
        messageBroker.publishToUser(userId, message);
    }

    /**
     * 仅向本机 session 发送消息，不转发 Redis
     * 供 WebSocketMessageBroker 的订阅回调调用，避免消息在实例间循环传播
     *
     * @return true 表示找到本机 session 并成功发送（或发送失败已清理）；false 表示本机无此用户
     */
    public boolean sendToLocalUser(Long userId, String message) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null) {
            log.debug("用户 {} 未在本机连接 WebSocket", userId);
            return false;
        }
        if (!session.isOpen()) {
            userSessions.remove(userId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(message));
            log.debug("已向用户 {} 推送消息（本机）", userId);
            return true;
        } catch (IOException e) {
            log.warn("向用户 {} 推送消息失败: {}", userId, e.getMessage());
            userSessions.remove(userId);
            return false;
        }
    }

    /**
     * 向所有在线客服广播消息（跨实例）
     *
     * 始终通过 Redis 广播：所有实例（包括本机）都会收到消息并向自己的本机 CS session 投递。
     * 这样无需维护全局 CS 在线列表，每个实例只管自己的。
     */
    public void sendToCs(String message) {
        messageBroker.publishToCs(message);
    }

    /**
     * 仅向本机 CS session 广播，供 Redis 订阅回调调用
     */
    public void sendToLocalCs(String message) {
        List<Long> toRemove = new ArrayList<>();
        for (var entry : csSessions.entrySet()) {
            if (entry.getValue().isOpen()) {
                try {
                    entry.getValue().sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    toRemove.add(entry.getKey());
                }
            } else {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(csSessions::remove);
        log.debug("已向本机 {} 名客服广播消息", csSessions.size());
    }

    public int getOnlineCount() { return userSessions.size(); }
    public int getOnlineCsCount() { return csSessions.size(); }
}

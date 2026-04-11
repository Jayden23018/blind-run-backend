package com.example.demo.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 WebSocket 会话注册表 —— 管理所有用户（盲人、志愿者、客服）的 WebSocket 连接
 * 在原有 VolunteerSessionRegistry 基础上扩展，支持多角色多频道推送
 */
@Slf4j
@Component
public class UnifiedSessionRegistry {

    /** 用户ID → WebSocket 会话 的映射表（所有角色共用） */
    private final ConcurrentHashMap<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    /** 客服ID 列表（用于广播紧急事件） */
    private final ConcurrentHashMap<Long, WebSocketSession> csSessions = new ConcurrentHashMap<>();

    /**
     * 注册用户连接
     */
    public void register(Long userId, WebSocketSession session, String role) {
        WebSocketSession oldSession = userSessions.put(userId, session);
        if (oldSession != null && oldSession.isOpen()) {
            try {
                oldSession.close();
            } catch (IOException e) {
                log.warn("关闭用户 {} 的旧 WebSocket 连接失败", userId);
            }
        }

        // 如果是客服，同时注册到客服列表
        if ("CS".equals(role)) {
            csSessions.put(userId, session);
        }

        log.info("用户 {} (role={}) WebSocket 已连接，当前在线: {}", userId, role, userSessions.size());
    }

    /**
     * 注册用户连接（无角色参数，默认普通用户）
     */
    public void register(Long userId, WebSocketSession session) {
        register(userId, session, null);
    }

    /**
     * 注销用户连接
     */
    public void unregister(Long userId) {
        userSessions.remove(userId);
        csSessions.remove(userId);
        log.info("用户 {} WebSocket 已断开，当前在线: {}", userId, userSessions.size());
    }

    /**
     * 获取用户的 WebSocket 会话
     */
    public Optional<WebSocketSession> getSession(Long userId) {
        return Optional.ofNullable(userSessions.get(userId));
    }

    /**
     * 向指定用户发送消息
     */
    public void sendToUser(Long userId, String message) {
        Optional<WebSocketSession> sessionOpt = getSession(userId);
        if (sessionOpt.isEmpty()) {
            log.debug("用户 {} 未连接 WebSocket，跳过推送", userId);
            return;
        }

        WebSocketSession session = sessionOpt.get();
        if (!session.isOpen()) {
            userSessions.remove(userId);
            return;
        }

        try {
            session.sendMessage(new TextMessage(message));
            log.debug("已向用户 {} 推送消息", userId);
        } catch (IOException e) {
            log.warn("向用户 {} 推送消息失败: {}", userId, e.getMessage());
            userSessions.remove(userId);
        }
    }

    /**
     * 向所有在线客服广播消息（紧急事件用）
     */
    public void sendToCs(String message) {
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
        log.debug("已向 {} 名客服广播消息", csSessions.size());
    }

    /**
     * 获取在线用户数
     */
    public int getOnlineCount() {
        return userSessions.size();
    }

    /**
     * 获取在线客服数
     */
    public int getOnlineCsCount() {
        return csSessions.size();
    }
}

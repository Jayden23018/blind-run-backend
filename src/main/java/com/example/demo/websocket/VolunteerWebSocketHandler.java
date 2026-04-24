package com.example.demo.websocket;

import com.example.demo.service.VolunteerLocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 志愿者 WebSocket 处理器 —— 处理志愿者的 WebSocket 连接事件和位置上报
 *
 * 【连接管理】
 * - 连接建立/断开：注册/注销到 UnifiedSessionRegistry
 * - userId 由 JwtHandshakeInterceptor 在握手时存入 session attributes
 *
 * 【位置上报】
 * - 前端通过 WebSocket 发送 JSON 消息上报实时位置
 * - 消息格式：{ "type": "LOCATION_UPDATE", "lat": 31.23, "lng": 121.47 }
 * - 调用 VolunteerLocationService 更新 Redis + MySQL
 */
@Slf4j
@Component
public class VolunteerWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_MESSAGE_SIZE = 64 * 1024;  // 64KB
    private static final long MIN_MESSAGE_INTERVAL_MS = 500; // 500ms

    private final ConcurrentHashMap<Long, Long> lastMessageTime = new ConcurrentHashMap<>();

    private final UnifiedSessionRegistry sessionRegistry;
    private final VolunteerLocationService volunteerLocationService;
    private final ObjectMapper objectMapper;

    public VolunteerWebSocketHandler(UnifiedSessionRegistry sessionRegistry,
                                     VolunteerLocationService volunteerLocationService,
                                     ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.volunteerLocationService = volunteerLocationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 连接建立成功时的回调
     * 从 session attributes 中取出 userId（由 JwtHandshakeInterceptor 设置）
     * 注册到 UnifiedSessionRegistry
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId != null) {
            sessionRegistry.register(userId, session);
        } else {
            log.warn("WebSocket 连接缺少用户ID信息，关闭连接");
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    /**
     * 处理前端发来的文本消息（主要是位置上报）
     *
     * 消息格式：{ "type": "LOCATION_UPDATE", "lat": 31.23, "lng": 121.47 }
     * 其他 type 的消息会被忽略并打印警告日志
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId == null) {
            log.warn("收到消息但 session 中无 userId，忽略");
            return;
        }

        // 消息大小限制
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            log.warn("志愿者 {} 消息超过大小限制 ({} bytes)", userId, message.getPayloadLength());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 速率限制
        long now = System.currentTimeMillis();
        Long last = lastMessageTime.get(userId);
        if (last != null && now - last < MIN_MESSAGE_INTERVAL_MS) {
            return;
        }
        lastMessageTime.put(userId, now);

        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.has("type") ? json.get("type").asText() : "";

            if ("LOCATION_UPDATE".equals(type)) {
                Double lat = json.has("lat") ? json.get("lat").asDouble() : null;
                Double lng = json.has("lng") ? json.get("lng").asDouble() : null;

                if (lat == null || lng == null) {
                    log.warn("志愿者 {} 位置消息缺少 lat/lng 字段", userId);
                    return;
                }

                volunteerLocationService.updateLocation(userId, lat, lng, true);
                log.debug("志愿者 {} WebSocket 位置上报: lat={}, lng={}", userId, lat, lng);
            } else {
                log.warn("志愿者 {} 发送未知消息类型: {}", userId, type);
            }
        } catch (Exception e) {
            log.error("志愿者 {} WebSocket 消息解析失败: {}", userId, e.getMessage());
        }
    }

    /**
     * 连接关闭时的回调
     * 从注册表中移除该志愿者
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId != null) {
            sessionRegistry.unregister(userId);
            lastMessageTime.remove(userId);
        }
    }

    /**
     * 传输错误时的回调
     * 记录日志并从注册表中移除
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        Long userId = getUserIdFromSession(session);
        log.error("志愿者 {} WebSocket 传输错误: {}", userId, exception.getMessage());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 从 WebSocket session 的 attributes 中提取用户ID
     * 这个值是在 JwtHandshakeInterceptor 中设置的
     */
    private Long getUserIdFromSession(WebSocketSession session) {
        Object userId = session.getAttributes().get("userId");
        return userId instanceof Long ? (Long) userId : null;
    }
}

package com.example.demo.websocket;

import com.example.demo.dto.BlindLocationRequest;
import com.example.demo.service.BlindLocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盲人用户 WebSocket 处理器 —— 处理盲人的 WebSocket 连接和位置上报
 *
 * 【连接管理】
 * - 连接建立/断开：注册/注销到 UnifiedSessionRegistry
 * - userId 由 JwtHandshakeInterceptor 在握手时存入 session attributes
 *
 * 【位置上报】
 * - 前端通过 WebSocket 发送 JSON 消息上报实时位置
 * - 消息格式：{ "type": "LOCATION_UPDATE", "lat": 31.23, "lng": 121.47 }
 * - 调用 BlindLocationService 更新 Redis
 *
 * 【接收推送】
 * - 志愿者位置更新：由 VolunteerLocationService.forwardLocationToBlind() 推送
 * - 订单状态变更：由 NotificationService 推送
 * - 紧急事件通知：由 EmergencyService 推送
 */
@Slf4j
@Component
public class BlindWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_MESSAGE_SIZE = 64 * 1024;
    private static final long MIN_MESSAGE_INTERVAL_MS = 500;

    private final ConcurrentHashMap<Long, Long> lastMessageTime = new ConcurrentHashMap<>();

    private final UnifiedSessionRegistry sessionRegistry;
    private final BlindLocationService blindLocationService;
    private final ObjectMapper objectMapper;

    public BlindWebSocketHandler(UnifiedSessionRegistry sessionRegistry,
                                  BlindLocationService blindLocationService,
                                  ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.blindLocationService = blindLocationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId != null) {
            sessionRegistry.register(userId, session);
            log.info("盲人用户 {} WebSocket 已连接", userId);
        } else {
            log.warn("WebSocket 连接缺少用户ID信息，关闭连接");
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId == null) {
            log.warn("收到消息但 session 中无 userId，忽略");
            return;
        }

        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            log.warn("盲人用户 {} 消息超过大小限制 ({} bytes)", userId, message.getPayloadLength());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastMessageTime.get(userId);
        if (last != null && now - last < MIN_MESSAGE_INTERVAL_MS) {
            return;
        }
        lastMessageTime.put(userId, now);

        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.has("type") ? json.get("type").asText() : "";

            switch (type) {
                case "LOCATION_UPDATE" -> {
                    Double lat = json.has("lat") ? json.get("lat").asDouble() : null;
                    Double lng = json.has("lng") ? json.get("lng").asDouble() : null;

                    if (lat == null || lng == null) {
                        log.warn("盲人用户 {} 位置消息缺少 lat/lng 字段", userId);
                        return;
                    }

                    BlindLocationRequest request = new BlindLocationRequest();
                    request.setLatitude(lat);
                    request.setLongitude(lng);
                    blindLocationService.updateLocation(userId, request);
                    log.debug("盲人用户 {} WebSocket 位置上报: lat={}, lng={}", userId, lat, lng);
                }
                case "PING" -> {
                    String pong = objectMapper.writeValueAsString(
                            Map.of("type", "PONG", "timestamp", System.currentTimeMillis()));
                    session.sendMessage(new TextMessage(pong));
                }
                default -> log.warn("盲人用户 {} 发送未知消息类型: {}", userId, type);
            }
        } catch (Exception e) {
            log.error("盲人用户 {} WebSocket 消息解析失败: {}", userId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = getUserIdFromSession(session);
        if (userId != null) {
            sessionRegistry.unregister(userId);
            lastMessageTime.remove(userId);
            log.info("盲人用户 {} WebSocket 已断开", userId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        Long userId = getUserIdFromSession(session);
        log.error("盲人用户 {} WebSocket 传输错误: {}", userId, exception.getMessage());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private Long getUserIdFromSession(WebSocketSession session) {
        Object userId = session.getAttributes().get("userId");
        return userId instanceof Long ? (Long) userId : null;
    }
}

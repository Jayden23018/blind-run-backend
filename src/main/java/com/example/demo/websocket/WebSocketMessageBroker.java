package com.example.demo.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 跨实例消息转发 —— 基于 Redis Pub/Sub
 *
 * 解决问题：多实例部署时，用户可能连接到任意一台服务器；
 * 当本机找不到目标 session 时，将消息发布到 Redis，
 * 持有该 session 的实例收到消息后完成最终推送。
 *
 * 消息格式：
 *   用户消息:   {"type":"USER",         "userId":123,  "payload":"..."}
 *   CS广播:     {"type":"CS_BROADCAST",               "payload":"..."}
 */
@Slf4j
@Component
public class WebSocketMessageBroker {

    public static final String WS_CHANNEL = "ws:messages";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    // @Lazy 注入，由 Spring 在第一次使用时解析，避免与 UnifiedSessionRegistry 的循环依赖
    private final UnifiedSessionRegistry sessionRegistry;

    public WebSocketMessageBroker(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   @org.springframework.context.annotation.Lazy UnifiedSessionRegistry sessionRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 将指定用户的消息发布到 Redis，供其他实例转发
     */
    public void publishToUser(Long userId, String payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "USER");
            envelope.put("userId", userId);
            envelope.put("payload", payload);
            redisTemplate.convertAndSend(WS_CHANNEL, objectMapper.writeValueAsString(envelope));
            log.debug("已发布 WebSocket 消息到 Redis，目标用户: {}", userId);
        } catch (Exception e) {
            log.warn("发布 WebSocket 用户消息失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 将 CS 广播消息发布到 Redis，所有实例均会投递给自己的本地 CS session
     */
    public void publishToCs(String payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "CS_BROADCAST");
            envelope.put("payload", payload);
            redisTemplate.convertAndSend(WS_CHANNEL, objectMapper.writeValueAsString(envelope));
            log.debug("已发布 WebSocket CS 广播消息到 Redis");
        } catch (Exception e) {
            log.warn("发布 WebSocket CS 广播消息失败: {}", e.getMessage());
        }
    }

    /**
     * Redis 订阅回调 —— 由 RedisWebSocketConfig 中的 MessageListenerAdapter 调用
     *
     * 收到消息后：
     * - USER 类型：尝试向本机 session 投递；若本机无此用户 session，静默跳过
     * - CS_BROADCAST 类型：向本机所有 CS session 广播
     */
    public void onMessage(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.readValue(message, Map.class);
            String type = (String) envelope.get("type");
            String payload = (String) envelope.get("payload");

            if ("USER".equals(type)) {
                Object userIdObj = envelope.get("userId");
                if (userIdObj == null) return;
                Long userId = ((Number) userIdObj).longValue();
                sessionRegistry.sendToLocalUser(userId, payload);
            } else if ("CS_BROADCAST".equals(type)) {
                sessionRegistry.sendToLocalCs(payload);
            }
        } catch (Exception e) {
            log.warn("处理 Redis WebSocket 消息失败: {}", e.getMessage());
        }
    }
}

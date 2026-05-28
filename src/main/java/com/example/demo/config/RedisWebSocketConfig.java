package com.example.demo.config;

import com.example.demo.websocket.WebSocketMessageBroker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub 配置 —— WebSocket 跨实例消息转发
 *
 * 所有服务实例均订阅 ws:messages 频道：
 * - 收到 USER 类型消息时，检查本机是否持有目标 session，有则投递
 * - 收到 CS_BROADCAST 类型消息时，向本机所有 CS session 广播
 */
@Configuration
public class RedisWebSocketConfig {

    @Bean
    public MessageListenerAdapter wsMessageListenerAdapter(WebSocketMessageBroker broker) {
        // 将 Redis 收到的消息路由到 WebSocketMessageBroker.onMessage(String)
        return new MessageListenerAdapter(broker, "onMessage");
    }

    @Bean
    public RedisMessageListenerContainer wsMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter wsMessageListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                wsMessageListenerAdapter,
                new ChannelTopic(WebSocketMessageBroker.WS_CHANNEL)
        );
        return container;
    }
}

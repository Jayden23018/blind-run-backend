package com.example.demo.config;

import com.example.demo.filter.JwtHandshakeInterceptor;
import com.example.demo.websocket.BlindWebSocketHandler;
import com.example.demo.websocket.VolunteerWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置类 —— 注册 WebSocket 端点和拦截器
 *
 * 【端点说明】
 * - /ws/volunteer → 志愿者 WebSocket（接收订单推送、上报位置）
 * - /ws/blind     → 盲人 WebSocket（上报位置、接收志愿者位置更新）
 *
 * 【认证方式】
 * 所有端点通过 JwtHandshakeInterceptor 在握手阶段验证 URL 中的 token 参数
 *
 * 【连接方式】
 * ws://localhost:8081/ws/volunteer?token=<jwt_token>
 * ws://localhost:8081/ws/blind?token=<jwt_token>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Value("${app.websocket.endpoint:/ws/volunteer}")
    private String volunteerEndpoint;

    @Value("${app.websocket.blind-endpoint:/ws/blind}")
    private String blindEndpoint;

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    private final VolunteerWebSocketHandler volunteerWebSocketHandler;
    private final BlindWebSocketHandler blindWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    public WebSocketConfig(VolunteerWebSocketHandler volunteerWebSocketHandler,
                           BlindWebSocketHandler blindWebSocketHandler,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.volunteerWebSocketHandler = volunteerWebSocketHandler;
        this.blindWebSocketHandler = blindWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = allowedOrigins.split(",");

        // 志愿者端点：接收订单推送、上报位置
        registry.addHandler(volunteerWebSocketHandler, volunteerEndpoint)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);

        // 盲人端点：上报位置、接收志愿者实时位置更新
        registry.addHandler(blindWebSocketHandler, blindEndpoint)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(origins);
    }
}

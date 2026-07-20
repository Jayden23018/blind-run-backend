package com.example.demo.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * UnifiedSessionRegistry 单元测试 —— 死连接检测（closeStaleSessions）
 */
@ExtendWith(MockitoExtension.class)
class UnifiedSessionRegistryTest {

    @Mock private WebSocketMessageBroker messageBroker;
    @Mock private WebSocketSession session;

    private UnifiedSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new UnifiedSessionRegistry(messageBroker);
    }

    @Test
    void closeStaleSessions_pastTimeout_closesSession() throws Exception {
        registry.register(1L, session);

        registry.closeStaleSessions(0);

        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void closeStaleSessions_withinTimeout_keepsSessionOpen() throws Exception {
        registry.register(1L, session);

        registry.closeStaleSessions(60_000);

        verify(session, never()).close(any());
    }

    @Test
    void touch_refreshesActivity_preventsStaleClose() throws Exception {
        registry.register(1L, session);
        // 模拟很久之前注册：把最后活跃时间强行调早，再 touch 刷新
        @SuppressWarnings("unchecked")
        var lastActivityTime = (java.util.concurrent.ConcurrentHashMap<Long, Long>)
                ReflectionTestUtils.getField(registry, "lastActivityTime");
        lastActivityTime.put(1L, System.currentTimeMillis() - 100_000);

        registry.touch(1L);
        registry.closeStaleSessions(60_000);

        verify(session, never()).close(any());
    }

    @Test
    void unregister_removesActivityRecord() {
        registry.register(1L, session);
        registry.unregister(1L);

        @SuppressWarnings("unchecked")
        var lastActivityTime = (java.util.concurrent.ConcurrentHashMap<Long, Long>)
                ReflectionTestUtils.getField(registry, "lastActivityTime");
        assertThat(lastActivityTime).doesNotContainKey(1L);
    }
}

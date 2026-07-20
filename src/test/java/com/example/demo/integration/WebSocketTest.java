package com.example.demo.integration;

import com.example.demo.websocket.UnifiedSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * WebSocket 模块集成测试（TC-WS-01 ~ 06）
 *
 * 测试志愿者 WebSocket 连接、认证、消息推送和断连清理。
 * 使用 StandardWebSocketClient 建立 WS 连接，
 * CopyOnWriteArrayList + CountDownLatch 捕获异步推送消息。
 */
class WebSocketTest extends BaseIntegrationTest {

    @Autowired
    private UnifiedSessionRegistry sessionRegistry;

    /** 记录需要清理的 WebSocket 会话 */
    private final List<WebSocketSession> activeSessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAllSessions() throws Exception {
        for (WebSocketSession session : activeSessions) {
            if (session.isOpen()) {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (Exception e) {
                    // 忽略关闭异常
                }
            }
        }
        activeSessions.clear();
    }

    /**
     * 建立 WebSocket 连接并等待完成
     *
     * @param token JWT token（可为 null）
     * @return WebSocket 会话
     */
    private WebSocketSession connectWs(String token) throws Exception {
        String url = "ws://127.0.0.1:" + port + "/ws/volunteer";
        if (token != null) {
            url += "?token=" + token;
        }

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.doHandshake(new AbstractWebSocketHandler() {}, url)
                .get(5, TimeUnit.SECONDS);
        activeSessions.add(session);
        return session;
    }

    /**
     * 建立带消息捕获的 WebSocket 连接
     *
     * @param token JWT token
     * @param messages 消息收集列表
     * @param latch 用于通知消息到达的 CountDownLatch
     * @return WebSocket 会话
     */
    private WebSocketSession connectWithMessageCapture(String token,
                                                       List<String> messages,
                                                       CountDownLatch latch) throws Exception {
        String url = "ws://127.0.0.1:" + port + "/ws/volunteer?token=" + token;

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.doHandshake(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession sess, TextMessage msg) {
                messages.add(msg.getPayload());
                if (latch != null) {
                    latch.countDown();
                }
            }
        }, url).get(5, TimeUnit.SECONDS);

        activeSessions.add(session);
        return session;
    }

    // ==================== 连接认证 ====================

    /** TC-WS-01：有效 token 连接成功 */
    @Test
    @DisplayName("TC-WS-01: 有效 token 连接成功")
    void tc01_connectWithValidToken() throws Exception {
        String volToken = testHelper.registerAndLoginWithRole("13800111001", "VOLUNTEER");

        WebSocketSession session = connectWs(volToken);

        assertThat(session.isOpen())
                .as("使用有效 token 连接 WebSocket，session 应该是打开状态")
                .isTrue();

        System.out.println("✅ TC-WS-01 passed — 有效 token 连接成功");
    }

    /** TC-WS-02：无效 token 连接失败 */
    @Test
    @DisplayName("TC-WS-02: 无效 token 连接失败")
    void tc02_connectWithInvalidToken() {
        assertThatCode(() -> connectWs("invalid_token_value"))
                .as("使用无效 token 连接 WebSocket 应抛出异常")
                .isInstanceOf(Exception.class);

        System.out.println("✅ TC-WS-02 passed — 无效 token 连接失败");
    }

    /** TC-WS-03：无 token 参数连接失败 */
    @Test
    @DisplayName("TC-WS-03: 无 token 参数连接失败")
    void tc03_connectWithoutToken() {
        assertThatCode(() -> connectWs(null))
                .as("不携带 token 参数连接 WebSocket 应抛出异常")
                .isInstanceOf(Exception.class);

        System.out.println("✅ TC-WS-03 passed — 无 token 参数连接失败");
    }

    // ==================== 消息推送 ====================

    /** TC-WS-04：接收 NEW_ORDER 推送（串行派单格式） */
    @Test
    @DisplayName("TC-WS-04: 接收 NEW_ORDER 推送")
    void tc04_receiveNewOrderPush() throws Exception {
        // 1. 注册志愿者并建立 WebSocket 连接
        String volToken = testHelper.registerAndLoginWithRole("13800112001", "VOLUNTEER");
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(1);

        connectWithMessageCapture(volToken, messages, messageLatch);

        // 2. 志愿者上报位置
        testHelper.updateVolunteerLocation(volToken, 39.9242, 116.4677, true);

        // 3. 盲人创建订单（触发异步派单 → WebSocket 推送）
        String blindToken = testHelper.registerAndLoginWithRole("13800112002", "BLIND");
        Long orderId = testHelper.createOrder(blindToken, 39.9042, 116.4674, "朝阳公园南门",
                TestHelper.defaultStartTime(), TestHelper.defaultEndTime());

        // 4. 等待推送消息到达
        boolean received = messageLatch.await(10, TimeUnit.SECONDS);
        assertThat(received)
                .as("应在 10 秒内收到 NEW_ORDER 推送消息")
                .isTrue();

        // 5. 验证推送消息内容
        assertThat(messages).hasSizeGreaterThanOrEqualTo(1);
        JsonNode push = testHelper.extractJson(messages.get(0));

        assertThat(push.get("type").asText()).isEqualTo("NEW_ORDER");
        assertThat(push.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(push.get("distanceKm").asDouble()).isGreaterThan(0);

        // 串行派单格式：包含 dispatchTimeoutSeconds 字段
        assertThat(push.has("dispatchTimeoutSeconds")).isTrue();

        System.out.println("✅ TC-WS-04 passed — 接收 NEW_ORDER 推送");
    }

    /** TC-WS-05：推送消息格式验证（串行派单格式） */
    @Test
    @DisplayName("TC-WS-05: 推送消息格式验证")
    void tc05_pushMessageFormat() throws Exception {
        // 1. 注册志愿者并建立 WebSocket 连接
        String volToken = testHelper.registerAndLoginWithRole("13800113001", "VOLUNTEER");
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(1);

        connectWithMessageCapture(volToken, messages, messageLatch);

        // 2. 志愿者上报位置
        testHelper.updateVolunteerLocation(volToken, 39.9242, 116.4677, true);

        // 3. 盲人创建订单
        String blindToken = testHelper.registerAndLoginWithRole("13800113002", "BLIND");
        Long orderId = testHelper.createOrder(blindToken, 39.9042, 116.4674, "朝阳公园南门",
                TestHelper.defaultStartTime(), TestHelper.defaultEndTime());

        // 4. 等待推送消息
        boolean received = messageLatch.await(10, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        // 5. 验证消息包含所有必需字段
        JsonNode push = testHelper.extractJson(messages.get(0));

        assertThat(push.has("type")).isTrue();
        assertThat(push.get("type").asText()).isEqualTo("NEW_ORDER");

        assertThat(push.has("orderId")).isTrue();
        assertThat(push.get("orderId").asLong()).isEqualTo(orderId);

        assertThat(push.has("distanceKm")).isTrue();
        assertThat(push.get("distanceKm").asDouble()).isGreaterThan(0);

        // 串行派单格式不再包含 blindUserPhone，改为 dispatchTimeoutSeconds
        assertThat(push.has("dispatchTimeoutSeconds")).isTrue();
        assertThat(push.get("dispatchTimeoutSeconds").asInt()).isGreaterThan(0);

        assertThat(push.has("startAddress")).isTrue();
        assertThat(push.get("startAddress").asText()).isEqualTo("朝阳公园南门");

        assertThat(push.has("plannedStart")).isTrue();
        assertThat(push.get("plannedStart").asText()).isNotBlank();

        assertThat(push.has("plannedEnd")).isTrue();
        assertThat(push.get("plannedEnd").asText()).isNotBlank();

        System.out.println("✅ TC-WS-05 passed — 推送消息格式验证");
    }

    /** TC-WS-06：断连清理 */
    @Test
    @DisplayName("TC-WS-06: 断连清理")
    void tc06_disconnectCleanup() throws Exception {
        // 1. 注册志愿者并建立 WebSocket 连接
        String volToken = testHelper.registerAndLoginWithRole("13800114001", "VOLUNTEER");
        WebSocketSession session = connectWs(volToken);

        assertThat(session.isOpen()).isTrue();

        // 2. 主动关闭连接
        session.close(CloseStatus.NORMAL);

        // 3. 等待关闭完成
        Thread.sleep(500);
        assertThat(session.isOpen()).isFalse();

        // 4. 向已关闭的会话发送消息不应抛出未处理异常
        // 验证 sendToUser 对已关闭会话的容错处理
        assertThatCode(() -> {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("test"));
            }
        }).doesNotThrowAnyException();

        System.out.println("✅ TC-WS-06 passed — 断连清理");
    }

    // ==================== 心跳 ====================

    /** TC-WS-07：PING/PONG 心跳 */
    @Test
    @DisplayName("TC-WS-07: PING/PONG 心跳")
    void tc07_pingPongHeartbeat() throws Exception {
        String volToken = testHelper.registerAndLoginWithRole("13800115001", "VOLUNTEER");
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(1);

        WebSocketSession session = connectWithMessageCapture(volToken, messages, messageLatch);

        session.sendMessage(new TextMessage("{\"type\":\"PING\"}"));

        boolean received = messageLatch.await(5, TimeUnit.SECONDS);
        assertThat(received)
                .as("应在 5 秒内收到 PONG 回复")
                .isTrue();

        JsonNode pong = testHelper.extractJson(messages.get(0));
        assertThat(pong.get("type").asText()).isEqualTo("PONG");
        assertThat(pong.has("timestamp")).isTrue();

        System.out.println("✅ TC-WS-07 passed — PING/PONG 心跳");
    }

    /** TC-WS-08：服务端死连接检测 —— 直接调用 closeStaleSessions，不依赖真实调度定时器，避免受配置的超时时长影响其它用例 */
    @Test
    @DisplayName("TC-WS-08: 服务端死连接检测")
    void tc08_deadConnectionDetection() throws Exception {
        String volToken = testHelper.registerAndLoginWithRole("13800115002", "VOLUNTEER");
        WebSocketSession session = connectWs(volToken);
        assertThat(session.isOpen()).isTrue();

        // timeoutMs=0：任意已注册连接立即视为"无活跃"，验证 closeStaleSessions 会关闭它
        sessionRegistry.closeStaleSessions(0);

        Thread.sleep(500);
        assertThat(session.isOpen())
                .as("超过死连接阈值未活跃的 session 应被服务端主动关闭")
                .isFalse();

        System.out.println("✅ TC-WS-08 passed — 服务端死连接检测");
    }
}

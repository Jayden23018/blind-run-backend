package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 陪跑轨迹回放测试：GET /api/orders/{id}/track 权限校验 + 轨迹落库
 */
class OrderTrackTest extends BaseIntegrationTest {

    /** 非参与者查询他人订单轨迹 → 403 */
    @Test
    @DisplayName("非参与者查询订单轨迹返回403")
    void strangerGetTrack_returns403() throws Exception {
        TestHelper.FlowResult flow = testHelper.setupOrderInProgress("13800080001", "13800080002");

        String strangerToken = testHelper.registerAndLogin("13800080003");
        ResponseEntity<String> response = testHelper.getOrderTrack(strangerToken, flow.orderId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode json = testHelper.extractJson(response.getBody());
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asInt()).isEqualTo(403);

        System.out.println("✅ OrderTrackTest passed — 非参与者查询订单轨迹返回403");
    }

    /** IN_PROGRESS 阶段双方上报位置后，能通过 /track 查到各自的轨迹点 */
    @Test
    @DisplayName("陪跑进行中位置上报后可查到双方轨迹点")
    void locationReportsDuringProgress_areQueryableViaTrack() throws Exception {
        TestHelper.FlowResult flow = testHelper.setupOrderInProgress("13800080011", "13800080012");

        testHelper.updateVolunteerLocation(flow.volToken(), 39.9242, 116.4677, true);
        testHelper.updateBlindLocation(flow.blindToken(), 39.9042, 116.4674);

        ResponseEntity<String> response = testHelper.getOrderTrack(flow.blindToken(), flow.orderId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = testHelper.extractJson(response.getBody());
        assertThat(json.get("volunteerTrack").size()).isGreaterThanOrEqualTo(1);
        assertThat(json.get("blindTrack").size()).isGreaterThanOrEqualTo(1);

        System.out.println("✅ OrderTrackTest passed — 陪跑进行中位置上报后可查到双方轨迹点");
    }

    /** /track 响应携带订单当前状态，供前端区分"未到陪跑阶段"/"进行中数据不足"/"历史订单不支持" */
    @Test
    @DisplayName("track响应包含订单状态字段")
    void trackResponse_includesOrderStatus() throws Exception {
        TestHelper.FlowResult flow = testHelper.setupOrderInProgress("13800080021", "13800080022");

        ResponseEntity<String> response = testHelper.getOrderTrack(flow.blindToken(), flow.orderId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = testHelper.extractJson(response.getBody());
        assertThat(json.get("status").asText()).isEqualTo("IN_PROGRESS");

        System.out.println("✅ OrderTrackTest passed — track响应包含订单状态字段");
    }
}

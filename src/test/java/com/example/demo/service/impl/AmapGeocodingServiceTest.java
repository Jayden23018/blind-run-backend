package com.example.demo.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AmapGeocodingService 单元测试 —— 验证降级前提分支（不发真实 HTTP 请求）。
 *
 * 这两个分支是 A2 "拿到 key 前可先上线" 的关键保证：未配置 key / 坐标缺失时
 * 立即返回 empty，上层 formatLocation 走可读经纬度降级，短信仍可发出。
 */
class AmapGeocodingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reverseGeocode_returnsEmpty_whenKeyBlank() {
        AmapGeocodingService service = new AmapGeocodingService("", objectMapper);

        assertTrue(service.reverseGeocode(new BigDecimal("31.23"), new BigDecimal("121.47")).isEmpty());
    }

    @Test
    void reverseGeocode_returnsEmpty_whenCoordsNull() {
        AmapGeocodingService service = new AmapGeocodingService("some-key", objectMapper);

        assertTrue(service.reverseGeocode(null, null).isEmpty());
    }
}

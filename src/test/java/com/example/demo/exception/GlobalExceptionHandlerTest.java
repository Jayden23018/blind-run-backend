package com.example.demo.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 行为单测 —— 覆盖 B3 修复：不存在的端点/资源必须返回 404，而非误导性 500。
 *
 * 背景：Spring Boot 3 对不存在的路径抛 {@link NoResourceFoundException}，
 * 若 GlobalExceptionHandler 未显式捕获，会落入兜底 handleGenericException 返回 500「服务器内部错误」，
 * 把"端点打错/不存在"伪装成服务器崩溃，误导前端无法区分（曾导致前端调已下线的 /accept 卡住主链路）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoResourceFoundException 返回 404 + NOT_FOUND，而非 500")
    void handleNoResourceFound_returns404() {
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.POST, "/api/orders/1/accept");

        var response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(false);
        assertThat(body.get("code")).isEqualTo(404);
        assertThat(body.get("errorCode")).isEqualTo("NOT_FOUND");
        assertThat(body.get("message")).asString().isNotEmpty();
    }
}

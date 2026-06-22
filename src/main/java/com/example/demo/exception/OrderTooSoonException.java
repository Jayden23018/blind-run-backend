package com.example.demo.exception;

/**
 * 预约提前量不足异常 —— 当预约开始时间距当前时间不足 {@code app.order.min-lead-time-minutes} 时抛出
 *
 * GlobalExceptionHandler 会捕获并返回 HTTP 422，响应体含 errorCode = APPOINTMENT_TOO_SOON。
 *
 * 【为什么用 422 而不是 400？】
 * 422 Unprocessable Entity 语义为"请求格式正确、可解析，但业务规则不允许"，
 * 比 400（参数语法错误）更准确地表达"时间值本身合法，只是太早"。
 */
public class OrderTooSoonException extends RuntimeException {

    private static final String ERROR_CODE = ErrorCode.APPOINTMENT_TOO_SOON.code();

    public OrderTooSoonException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}

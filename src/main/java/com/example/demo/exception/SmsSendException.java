package com.example.demo.exception;

/**
 * 短信发送异常 —— 短信服务商侧返回业务性失败（如流控、余额不足）时抛出。
 *
 * 与裸抛 RuntimeException 的区别：这类失败是服务商侧可预期的业务状态，不是系统故障，
 * 之前裸抛会被 GlobalExceptionHandler 兜底成 500「服务器内部错误」，掩盖真实原因
 * （曾导致单个手机号触发阿里云日流控后，误判为"登录不了"的系统性故障）。
 */
public class SmsSendException extends RuntimeException {

    private final ErrorCode errorCode;

    public SmsSendException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

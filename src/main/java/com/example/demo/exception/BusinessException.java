package com.example.demo.exception;

/**
 * 通用业务异常 —— 携带 errorCode 供前端区分场景，返回 HTTP 422
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

package com.example.demo.exception;

/**
 * 认证异常 —— 当登录/验证过程中出现问题时抛出
 */
public class AuthException extends RuntimeException {

    private final String errorCode;

    public AuthException(String message) {
        super(message);
        this.errorCode = null;
    }

    public AuthException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

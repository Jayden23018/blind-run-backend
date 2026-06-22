package com.example.demo.exception;

/**
 * 认证异常 —— 当登录/验证过程中出现问题时抛出
 *
 * 【什么是自定义异常？】
 * Java 有很多内置异常（如 NullPointerException），
 * 但我们需要表达 "验证码错误"、"手机号格式不对" 这类业务错误，
 * 所以自定义一个 AuthException 来专门处理认证相关的错误。
 *
 * 【RuntimeException vs Exception？】
 * RuntimeException 是 "非受检异常"，不需要在方法签名中声明 throws，
 * Spring 的事务管理遇到 RuntimeException 时会自动回滚。
 *
 * 【errorCode】
 * 带 errorCode 的构造器让前端能程序化区分不同认证失败场景
 * （如 INVALID_VERIFICATION_CODE / PHONE_FORMAT_INVALID / USER_NOT_FOUND），
 * GlobalExceptionHandler 会把 errorCode 写入响应体。
 */
public class AuthException extends RuntimeException {

    private final String errorCode;

    /** 单参构造：errorCode 为 null，兼容不区分场景的调用 */
    public AuthException(String message) {
        super(message);
        this.errorCode = null;
    }

    /** 明确场景构造：前端可根据 errorCode 做不同处理 */
    public AuthException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode.code();
    }

    public String getErrorCode() {
        return errorCode;
    }
}

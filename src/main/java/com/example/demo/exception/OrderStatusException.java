package com.example.demo.exception;

/**
 * 订单状态异常 —— 当订单状态流转不合法时抛出
 *
 * 例如：接单时订单状态不是 PENDING_ACCEPT，或结束服务时订单不在进行中状态
 * GlobalExceptionHandler 会捕获它并返回 HTTP 409。
 *
 * 【errorCode】
 * 仿 OrderPermissionException：单参构造默认 errorCode=ORDER_STATUS_NOT_ALLOWED，
 * 双参构造可指定具体场景（如 ORDER_ALREADY_ACCEPTED / ORDER_DISPATCH_MISMATCH / ORDER_CONCURRENT_CONFLICT），
 * 让前端能程序化区分"已被他人接单"等场景，而非只能靠 message 文本判断。
 */
public class OrderStatusException extends RuntimeException {

    private final String errorCode;

    /** 兜底构造：errorCode 默认为 ORDER_STATUS_NOT_ALLOWED */
    public OrderStatusException(String message) {
        super(message);
        this.errorCode = ErrorCode.ORDER_STATUS_NOT_ALLOWED.code();
    }

    /** 明确场景构造：前端可根据 errorCode 区分不同失败原因 */
    public OrderStatusException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode.code();
    }

    public String getErrorCode() {
        return errorCode;
    }
}

package com.example.demo.exception;

/**
 * 订单权限异常 —— 当用户无权操作某个订单时抛出
 *
 * 使用带 errorCode 的构造器让前端区分不同场景：
 *   VOLUNTEER_NOT_REGISTERED  — 志愿者注册流程未完成
 *   VOLUNTEER_NOT_VERIFIED    — 志愿者证件审核未通过
 *   NOT_ORDER_PARTICIPANT     — 非订单参与者越权操作
 *   ORDER_IN_PROGRESS         — 订单进行中，操作受限
 *   ORDER_PERMISSION_DENIED   — 兜底通用权限拒绝
 *
 * GlobalExceptionHandler 会捕获并返回 HTTP 403，响应体含 errorCode 字段。
 */
public class OrderPermissionException extends RuntimeException {

    private final String errorCode;

    /** 兜底构造：errorCode 默认为 ORDER_PERMISSION_DENIED */
    public OrderPermissionException(String message) {
        super(message);
        this.errorCode = "ORDER_PERMISSION_DENIED";
    }

    /** 明确场景构造：前端可根据 errorCode 跳转不同页面 */
    public OrderPermissionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

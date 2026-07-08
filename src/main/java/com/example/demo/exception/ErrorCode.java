package com.example.demo.exception;

/**
 * 业务错误码枚举 —— 集中管理所有对前端暴露的 errorCode 字符串
 *
 * 【为什么要集中？】
 * 之前 errorCode 是散落在各 Service 里的字符串字面量（如 "VOLUNTEER_NOT_VERIFIED"），
 * 容易拼写漂移、难以检索。集中到枚举后：
 *   - 改名时编译器会指出所有引用点
 *   - 一眼能看到系统对外暴露的全部错误码
 *   - 新增码时自带 HTTP 状态码映射，避免 handler 写错
 *
 * 【字段说明】
 *   - code：对外暴露的字符串（前端据此分支）
 *   - httpStatus：对应 HTTP 状态码（GlobalExceptionHandler 映射时参考）
 *
 * 【使用方式】
 *   throw new OrderPermissionException(ErrorCode.VOLUNTEER_NOT_AVAILABLE, "...");
 *   异常类的 errorCode 字段最终会被 GlobalExceptionHandler 写入响应体。
 */
public enum ErrorCode {

    // ===== 认证类（400/404）=====
    /** 验证码错误或已过期 */
    INVALID_VERIFICATION_CODE("INVALID_VERIFICATION_CODE", 400),
    /** 手机号格式不正确 */
    PHONE_FORMAT_INVALID("PHONE_FORMAT_INVALID", 400),
    /** 用户不存在 */
    USER_NOT_FOUND("USER_NOT_FOUND", 404),

    // ===== 实名核验类（400）=====
    /** 身份证信息核验未通过（二要素不一致或格式不合法） */
    ID_INFO_INVALID("ID_INFO_INVALID", 400),

    // ===== 志愿者资质类（403）=====
    /** 志愿者已关闭可服务状态，可浏览订单但不能接单 */
    VOLUNTEER_NOT_AVAILABLE("VOLUNTEER_NOT_AVAILABLE", 403),
    /** 志愿者注册流程未完成 */
    VOLUNTEER_NOT_REGISTERED("VOLUNTEER_NOT_REGISTERED", 403),
    /** 志愿者证件审核未通过 */
    VOLUNTEER_NOT_VERIFIED("VOLUNTEER_NOT_VERIFIED", 403),
    /** 非订单参与者越权操作 */
    NOT_ORDER_PARTICIPANT("NOT_ORDER_PARTICIPANT", 403),
    /** 订单进行中，操作受限 */
    ORDER_IN_PROGRESS("ORDER_IN_PROGRESS", 403),
    /** 兜底通用权限拒绝 */
    ORDER_PERMISSION_DENIED("ORDER_PERMISSION_DENIED", 403),

    // ===== 订单状态类（409）=====
    /** 订单已被他人接单 / 状态不允许接单 */
    ORDER_ALREADY_ACCEPTED("ORDER_ALREADY_ACCEPTED", 409),
    /** 订单状态流转不合法（通用） */
    ORDER_STATUS_NOT_ALLOWED("ORDER_STATUS_NOT_ALLOWED", 409),
    /** 该订单当前未派送给该志愿者 */
    ORDER_DISPATCH_MISMATCH("ORDER_DISPATCH_MISMATCH", 409),
    /** 订单并发冲突（乐观锁/分布式锁抢占失败） */
    ORDER_CONCURRENT_CONFLICT("ORDER_CONCURRENT_CONFLICT", 409),

    // ===== 订单创建类（422）=====
    /** 预约开始时间距当前时间不足阈值 */
    APPOINTMENT_TOO_SOON("APPOINTMENT_TOO_SOON", 422);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** 对外暴露的字符串码 */
    public String code() {
        return code;
    }

    /** 对应 HTTP 状态码 */
    public int httpStatus() {
        return httpStatus;
    }
}

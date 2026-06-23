package com.example.demo.exception;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 统一管理所有接口的错误返回格式
 *
 * 【统一错误格式】
 *   { "success": false, "code": <http状态码>, "errorCode": "<业务码>", "message": "<提示信息>" }
 * 其中 errorCode 为可空字段：需要前端程序化区分的场景（接单失败、验证码错误等）才填，
 * 通用异常可不填。新接口一律走这套结构，旧 { "error": "..." } 仅 ResourceNotFoundException 残留。
 *
 * 【错误码集中管理】
 * 见 {@link ErrorCode}，新增码请加到枚举里，避免散落字符串字面量。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 认证异常 → 400（统一结构，含 errorCode）
     * 常见 errorCode：INVALID_VERIFICATION_CODE / PHONE_FORMAT_INVALID / USER_NOT_FOUND
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuthException(AuthException e) {
        String errorCode = e.getErrorCode() != null
                ? e.getErrorCode()
                : ErrorCode.PHONE_FORMAT_INVALID.code();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "code", 400,
                        "errorCode", errorCode, "message", e.getMessage()));
    }

    /** 资源未找到 → 404（旧格式） */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    /** 订单不存在 → 404（新格式） */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "code", 404, "message", e.getMessage()));
    }

    /** 重复订单 → 409 */
    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateOrder(DuplicateOrderException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", 409, "message", e.getMessage()));
    }

    /** 预约提前量不足 → 422（含 errorCode = APPOINTMENT_TOO_SOON） */
    @ExceptionHandler(OrderTooSoonException.class)
    public ResponseEntity<Map<String, Object>> handleOrderTooSoon(OrderTooSoonException e) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("success", false, "code", 422,
                        "errorCode", e.getErrorCode(), "message", e.getMessage()));
    }

    /** 订单状态流转异常 → 409（统一结构，含 errorCode） */
    @ExceptionHandler(OrderStatusException.class)
    public ResponseEntity<Map<String, Object>> handleOrderStatus(OrderStatusException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", 409,
                        "errorCode", e.getErrorCode(), "message", e.getMessage()));
    }

    /** 角色已设定异常 → 409 */
    @ExceptionHandler(RoleAlreadySetException.class)
    public ResponseEntity<Map<String, Object>> handleRoleAlreadySet(RoleAlreadySetException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", 409, "message", e.getMessage()));
    }

    /** 订单权限异常 → 403 */
    @ExceptionHandler(OrderPermissionException.class)
    public ResponseEntity<Map<String, Object>> handleOrderPermission(OrderPermissionException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "code", 403,
                        "errorCode", e.getErrorCode(), "message", e.getMessage()));
    }

    /** 权限不足异常 → 403 */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> handlePermissionDenied(PermissionDeniedException e) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "code", 403, "message", e.getMessage()));
    }

    /** 参数校验异常 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "code", 400, "message", e.getMessage()));
    }

    /** @Valid 校验失败 → 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "code", 400, "message", errorMessage));
    }

    /** 乐观锁冲突 → 409 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", 409, "message", "订单已被其他志愿者接单"));
    }

    /** 注册步骤异常 → 409 */
    @ExceptionHandler(RegistrationStepException.class)
    public ResponseEntity<Map<String, Object>> handleRegistrationStep(RegistrationStepException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", 409, "message", e.getMessage()));
    }

    /** 培训业务异常 → 400 */
    @ExceptionHandler(TrainingException.class)
    public ResponseEntity<Map<String, Object>> handleTraining(TrainingException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "code", 400, "message", e.getMessage()));
    }

    /** 速率限制异常 → 429 */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitException(RateLimitException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "TOO_MANY_REQUESTS");
        body.put("message", e.getMessage());
        body.put("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(body);
    }

    /** 非法状态 → 500（不暴露内部错误信息） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("IllegalStateException: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "code", 500, "message", "服务器内部错误，请稍后重试"));
    }

    /**
     * 请求的端点/资源不存在 → 404（统一结构）
     * Spring Boot 3 对不存在的路径抛 NoResourceFoundException，必须显式捕获并返回 404；
     * 否则会被下方兜底 {@link #handleGenericException} 错误地返回 500「服务器内部错误」，
     * 把"端点打错/不存在"伪装成服务器崩溃，误导前端无法区分。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "code", 404,
                        "errorCode", "NOT_FOUND", "message", "请求的资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("未捕获异常: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "code", 500, "message", "服务器内部错误，请稍后重试"));
    }
}

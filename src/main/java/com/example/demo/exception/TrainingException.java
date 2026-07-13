package com.example.demo.exception;

/**
 * 培训业务异常 - 培训相关的业务逻辑错误
 * HTTP 状态码统一 400 Bad Request；errorCode 仅用于前端程序化区分具体场景，不影响状态码。
 *
 * 【errorCode】
 * 仿 OrderStatusException：单参构造默认 errorCode=TRAINING_ERROR，
 * 双参构造可指定具体场景（如 TRAINING_QUIZ_LOCKED / TRAINING_PROGRESS_REGRESSION），
 * 让前端能程序化区分失败原因，而非只能靠 message 文本判断。
 */
public class TrainingException extends RuntimeException {

    private final String errorCode;

    /** 兜底构造：errorCode 默认为 TRAINING_ERROR */
    public TrainingException(String message) {
        super(message);
        this.errorCode = ErrorCode.TRAINING_ERROR.code();
    }

    /** 明确场景构造：前端可根据 errorCode 区分不同失败原因 */
    public TrainingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode.code();
    }

    public TrainingException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.TRAINING_ERROR.code();
    }

    public String getErrorCode() {
        return errorCode;
    }
}

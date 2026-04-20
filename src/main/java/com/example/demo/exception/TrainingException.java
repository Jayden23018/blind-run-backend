package com.example.demo.exception;

/**
 * 培训业务异常 - 培训相关的业务逻辑错误
 * HTTP 状态码：400 Bad Request
 */
public class TrainingException extends RuntimeException {

    public TrainingException(String message) {
        super(message);
    }

    public TrainingException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.example.demo.exception;

/**
 * 注册步骤异常 - 志愿者未按正确流程完成注册步骤
 * HTTP 状态码：409 Conflict
 */
public class RegistrationStepException extends RuntimeException {

    public RegistrationStepException(String message) {
        super(message);
    }

    public RegistrationStepException(String message, Throwable cause) {
        super(message, cause);
    }
}

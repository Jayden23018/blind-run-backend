package com.example.demo.exception;

/**
 * 注册信息核验异常 —— 实名/身份证信息核验未通过时抛出。
 *
 * 与 {@link RegistrationStepException} 的区别：
 *   - RegistrationStepException：流程顺序错（步骤不对），HTTP 409，无 errorCode
 *   - RegistrationRejectedException：提交的数据本身未通过核验（如身份证二要素不一致），
 *     HTTP 400，带 errorCode（ID_INFO_INVALID），前端据此引导用户修改后重提。
 *
 * 参考 OrderPermissionException / OrderStatusException 的「带 errorCode 双构造器」模式。
 */
public class RegistrationRejectedException extends RuntimeException {

    private final ErrorCode errorCode;

    public RegistrationRejectedException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

package com.example.demo.entity;

/**
 * 志愿者注册步骤枚举
 */
public enum RegistrationStep {
    /**
     * 第1步：填写基本信息（姓名、手机）
     */
    STEP_1_BASIC_INFO,

    /**
     * 第2步：上传身份证照片和信息
     */
    STEP_2_ID_UPLOAD,

    /**
     * 第3步：人脸验证（系统自动处理，Stub）
     */
    STEP_3_FACE_VERIFY,

    /**
     * 第4步：培训学习中
     */
    STEP_4_TRAINING,

    /**
     * 培训完成，可以接单
     */
    STEP_4_COMPLETED
}

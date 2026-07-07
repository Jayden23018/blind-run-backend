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
     * 第2步：上传身份证照片和信息（已下线——动作活体改造后，身份证字段挪到 step1）。
     * 保留枚举值仅为兼容生产库历史数据；getRegistrationStatus 会把历史卡在此步的用户自动迁移到 STEP_3。
     */
    @Deprecated
    STEP_2_ID_UPLOAD,

    /**
     * 第3步：动作活体认证（InitFaceVerify/DescribeFaceVerify，前端打开 CertifyUrl 完成动作活体）
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

package com.example.demo.entity;

/**
 * 人脸验证状态枚举
 */
public enum FaceVerifyStatus {
    /** 未开始 */
    NOT_STARTED,
    /** 验证中（保留，用于异步场景） */
    PENDING,
    /** 验证通过 */
    APPROVED,
    /** 验证未通过（可重试） */
    REJECTED
}

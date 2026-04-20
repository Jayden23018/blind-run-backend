package com.example.demo.entity;

/**
 * 身份证验证状态枚举
 */
public enum IdVerifyStatus {
    /**
     * 未开始
     */
    NOT_STARTED,

    /**
     * 等待管理员审核
     */
    PENDING,

    /**
     * 审核通过
     */
    APPROVED,

    /**
     * 审核拒绝（可重新提交）
     */
    REJECTED
}

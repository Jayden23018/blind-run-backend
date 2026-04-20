package com.example.demo.entity;

/**
 * 盲人用户身份认证状态枚举
 */
public enum BlindVerifyStatus {
    /** 未认证 */
    NOT_VERIFIED,
    /** 认证通过 */
    VERIFIED,
    /** 认证失败（可重试） */
    FAILED
}

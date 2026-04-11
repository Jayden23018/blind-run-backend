package com.example.demo.entity;

/**
 * 通话记录状态
 */
public enum CallStatus {
    INITIATED,      // 已发起
    CONNECTED,      // 已接通
    FAILED,         // 失败
    NOT_AVAILABLE   // 隐私号未接入
}

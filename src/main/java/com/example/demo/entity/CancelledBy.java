package com.example.demo.entity;

/**
 * 取消方枚举 —— 记录是谁取消了订单
 *
 * BLIND     盲人用户取消
 * VOLUNTEER 志愿者取消
 * SYSTEM    系统自动取消（无志愿者/超时）
 */
public enum CancelledBy {
    BLIND,
    VOLUNTEER,
    SYSTEM
}

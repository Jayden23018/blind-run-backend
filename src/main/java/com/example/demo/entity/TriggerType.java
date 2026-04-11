package com.example.demo.entity;

/**
 * 紧急事件触发类型
 */
public enum TriggerType {
    BUTTON,        // 盲人手动按下紧急按钮
    AI_DETECTED,   // AI 自动检测到异常
    MANUAL         // 客服手动创建
}

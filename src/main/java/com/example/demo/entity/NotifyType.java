package com.example.demo.entity;

/**
 * 通知渠道类型
 */
public enum NotifyType {
    SMS_TO_CONTACT,   // 短信发给紧急联系人
    SMS_TO_USER,      // 短信发给盲人本人
    APP_PUSH,         // App 推送
    AI_VOICE_CALL     // AI 语音外呼
}

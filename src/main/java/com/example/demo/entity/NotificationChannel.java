package com.example.demo.entity;

/**
 * 通知渠道
 */
public enum NotificationChannel {
    APP_PUSH,    // App 推送（FCM）
    SMS,         // 短信
    WEBSOCKET    // WebSocket 实时推送
}

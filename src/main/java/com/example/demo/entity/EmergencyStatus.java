package com.example.demo.entity;

/**
 * 紧急事件状态
 */
public enum EmergencyStatus {
    PENDING,               // 刚触发，待处理
    VOLUNTEER_NOTIFIED,    // 已通知志愿者，等待确认
    VOLUNTEER_CONFIRMED,   // 志愿者已确认（需帮助）
    CS_HANDLING,           // 客服处理中
    CONTACT_NOTIFIED,      // 已通知紧急联系人
    RESOLVED,              // 已解决
    FALSE_ALARM            // 误触
}

package com.example.demo.entity;

/**
 * 志愿者对紧急事件的响应动作
 */
public enum VolunteerAction {
    FALSE_ALARM,   // 误触，无需进一步处理
    NEED_HELP      // 确认需要帮助
}

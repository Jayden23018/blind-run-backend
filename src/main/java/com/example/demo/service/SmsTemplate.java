package com.example.demo.service;

/**
 * 短信模板类型
 */
public enum SmsTemplate {
    /** 验证码：您的验证码为${code}，请勿泄露给他人。 */
    VERIFY_CODE,
    /** 紧急求助：紧急提醒：您的联系人${user_name}于${time}触发了紧急求助... */
    EMERGENCY_ALERT,
    /** 紧急联系人被添加：${user_name}已将您设为其紧急联系人... */
    CONTACT_ADDED,
    /** 紧急解除：通知：${user_name}的紧急求助已于${time}解除... */
    EMERGENCY_RESOLVED
}

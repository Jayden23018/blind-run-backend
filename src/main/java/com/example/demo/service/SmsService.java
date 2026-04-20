package com.example.demo.service;

import java.util.Map;

/**
 * 短信发送服务接口
 *
 * 实现类：AliyunSmsServiceImpl（阿里云短信服务 Dysmsapi）
 *         TestSmsServiceImpl（测试环境日志输出）
 */
public interface SmsService {

    /**
     * 发送短信验证码
     *
     * @param phone 目标手机号
     * @param code  验证码
     */
    void sendVerificationCode(String phone, String code);

    /**
     * 发送模板短信
     *
     * @param phone    目标手机号
     * @param template 短信模板类型
     * @param params   模板参数（如 code, user_name, time, location）
     */
    void sendTemplateSms(String phone, SmsTemplate template, Map<String, String> params);
}

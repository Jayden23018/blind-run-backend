package com.example.demo.service;

/**
 * 短信发送服务接口
 *
 * 实现类：AliyunSmsServiceImpl（阿里云号码认证服务）
 */
public interface SmsService {

    /**
     * 发送短信验证码
     *
     * @param phone 目标手机号
     * @param code  验证码
     */
    void sendVerificationCode(String phone, String code);
}

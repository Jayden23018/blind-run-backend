package com.example.demo.service.impl;

import com.example.demo.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 测试用短信服务 —— 不调用外部 API，仅记录日志
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sms.provider", havingValue = "test")
public class TestSmsServiceImpl implements SmsService {

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("【测试短信】手机号: {} 验证码: {}", phone, code);
    }
}

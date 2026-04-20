package com.example.demo.service.impl;

import com.example.demo.service.SmsService;
import com.example.demo.service.SmsTemplate;
import com.example.demo.util.PhoneMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 测试用短信服务 — 不调用外部 API，仅记录日志
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sms.provider", havingValue = "test")
public class TestSmsServiceImpl implements SmsService {

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.info("【测试短信】验证码: 手机号={} code={}", PhoneMaskUtils.mask(phone), code);
    }

    @Override
    public void sendTemplateSms(String phone, SmsTemplate template, Map<String, String> params) {
        log.info("【测试短信】模板短信: 手机号={}, 模板={}, 参数={}",
                PhoneMaskUtils.mask(phone), template, params);
    }
}

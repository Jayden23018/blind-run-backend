package com.example.demo.service.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.example.demo.service.SmsService;
import com.example.demo.service.SmsTemplate;
import com.example.demo.util.PhoneMaskUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 阿里云短信服务实现（Dysmsapi SendSms API）
 *
 * 支持自定义签名和模板，使用 V2 sync SDK 模式
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sms.provider", havingValue = "aliyun", matchIfMissing = true)
public class AliyunSmsServiceImpl implements SmsService {

    @Value("${aliyun.credentials.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.credentials.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.sms.sign-name:}")
    private String signName;

    @Value("${aliyun.sms.template-code.verify:}")
    private String verifyTemplateCode;

    @Value("${aliyun.sms.template-code.emergency-alert:}")
    private String emergencyAlertTemplateCode;

    @Value("${aliyun.sms.template-code.contact-added:}")
    private String contactAddedTemplateCode;

    @Value("${aliyun.sms.template-code.emergency-resolved:}")
    private String emergencyResolvedTemplateCode;

    private Client smsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws Exception {
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setEndpoint("dysmsapi.aliyuncs.com")
                .setConnectTimeout(5000)   // 连接超时 5s
                .setReadTimeout(10000);    // 读取超时 10s
        this.smsClient = new Client(config);
        log.info("阿里云短信客户端初始化完成（AccessKey: {}***）",
                accessKeyId.substring(0, Math.min(8, accessKeyId.length())));
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        sendTemplateSms(phone, SmsTemplate.VERIFY_CODE, Map.of("code", code));
    }

    @Override
    public void sendTemplateSms(String phone, SmsTemplate template, Map<String, String> params) {
        String templateCode = resolveTemplateCode(template);
        String templateParamJson = toJson(params);

        SendSmsRequest request = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam(templateParamJson);

        log.info("发送短信请求: signName={}, templateCode={}, phone={}",
                signName, templateCode, PhoneMaskUtils.mask(phone));

        try {
            SendSmsResponse response = smsClient.sendSms(request);
            SendSmsResponseBody body = response.getBody();

            if (!"OK".equals(body.getCode())) {
                log.error("阿里云短信发送失败: 手机号={}, 模板={}, Code={}, Message={}",
                        PhoneMaskUtils.mask(phone), template, body.getCode(), body.getMessage());
                throw new RuntimeException("短信发送失败: " + body.getMessage());
            }

            log.info("阿里云短信发送成功: 手机号={}, 模板={}, BizId={}, RequestId={}",
                    PhoneMaskUtils.mask(phone), template, body.getBizId(), body.getRequestId());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("阿里云短信发送异常: 手机号={}, 模板={}, 错误={}",
                    PhoneMaskUtils.mask(phone), template, e.getMessage(), e);
            throw new RuntimeException("短信发送失败: " + e.getMessage(), e);
        }
    }

    private String resolveTemplateCode(SmsTemplate template) {
        return switch (template) {
            case VERIFY_CODE -> verifyTemplateCode;
            case EMERGENCY_ALERT -> emergencyAlertTemplateCode;
            case CONTACT_ADDED -> contactAddedTemplateCode;
            case EMERGENCY_RESOLVED -> emergencyResolvedTemplateCode;
        };
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("短信模板参数序列化失败", e);
        }
    }
}

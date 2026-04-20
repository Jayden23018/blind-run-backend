package com.example.demo.service.impl;

import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.example.demo.service.SmsService;
import com.example.demo.util.PhoneMaskUtils;
import darabonba.core.client.ClientOverrideConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 阿里云号码认证服务实现类（免资质签名，开箱即用）
 *
 * 使用 dypnsapi 的 SendSmsVerifyCode API
 * - 支持免资质签名模板（赠送签名 + 赠送模板）
 * - 完全匹配官方示例代码的配置方式
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

    @Value("${aliyun.sms.template-code:100001}")
    private String templateCode;

    @Override
    public void sendVerificationCode(String phone, String code) {
        try (AsyncClient client = createClient()) {
            String templateParam = String.format("{\"code\":\"%s\",\"min\":\"5\"}", code);

            SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                    .signName(signName)
                    .templateCode(templateCode)
                    .templateParam(templateParam)
                    .phoneNumber(phone)
                    .build();

            CompletableFuture<SendSmsVerifyCodeResponse> responseFuture = client.sendSmsVerifyCode(request);
            SendSmsVerifyCodeResponse response = responseFuture.get();

            SendSmsVerifyCodeResponseBody body = response.getBody();
            log.info("阿里云短信响应: 手机号={}, Code={}, Message={}",
                    PhoneMaskUtils.mask(phone), body.getCode(), body.getMessage());

            if (!"OK".equals(body.getCode())) {
                log.error("阿里云短信发送失败: 手机号={}, 错误码={}, 错误消息={}",
                        PhoneMaskUtils.mask(phone), body.getCode(), body.getMessage());
                throw new RuntimeException("短信发送失败: " + body.getMessage());
            }

            log.info("阿里云短信发送成功: 手机号={}", PhoneMaskUtils.mask(phone));

        } catch (Exception e) {
            log.error("阿里云短信发送异常: 手机号={}, 错误={}", PhoneMaskUtils.mask(phone), e.getMessage(), e);
            throw new RuntimeException("短信发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建阿里云短信客户端（完全匹配官方示例）
     */
    private AsyncClient createClient() {
        log.info("初始化阿里云短信客户端（AccessKey: {}）",
                accessKeyId.substring(0, Math.min(8, accessKeyId.length())) + "***");

        com.aliyun.auth.credentials.Credential credential =
                com.aliyun.auth.credentials.Credential.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .build();

        com.aliyun.auth.credentials.provider.StaticCredentialProvider provider =
                com.aliyun.auth.credentials.provider.StaticCredentialProvider.create(credential);

        return AsyncClient.builder()
                .region("cn-hangzhou")
                .credentialsProvider(provider)
                .overrideConfiguration(
                        ClientOverrideConfiguration.create()
                                .setEndpointOverride("dypnsapi.aliyuncs.com")
                )
                .build();
    }
}

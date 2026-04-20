package com.example.demo.service.impl;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.ContrastFaceVerifyRequest;
import com.aliyun.cloudauth20190307.models.ContrastFaceVerifyResponse;
import com.aliyun.cloudauth20190307.models.ContrastFaceVerifyResponseBody;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyRequest;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponse;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponseBody;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.UUID;

/**
 * 阿里云金融级实人认证服务
 *
 * 志愿者端：ContrastFaceVerify（照片比对）
 * 盲人端：Id2MetaVerify（二要素核验）
 * 主备端点自动切换（Shanghai → Beijing）
 */
@Slf4j
@Service
public class AliyunIdVerifyService {

    @Value("${aliyun.credentials.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.credentials.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.cloudauth.scene-id:}")
    private String sceneIdStr;

    @Value("${aliyun.cloudauth.endpoint:cloudauth.cn-shanghai.aliyuncs.com}")
    private String primaryEndpoint;

    private static final String BACKUP_ENDPOINT = "cloudauth.cn-beijing.aliyuncs.com";

    /**
     * 志愿者人脸照片比对
     */
    public FaceVerifyResult contrastFaceVerify(String certName, String certNo, byte[] facePhoto) {
        String orderNo = generateOrderNo();
        String base64Photo = Base64.getEncoder().encodeToString(facePhoto);
        Long sceneId = parseSceneId();

        ContrastFaceVerifyRequest request = new ContrastFaceVerifyRequest()
                .setSceneId(sceneId)
                .setOuterOrderNo(orderNo)
                .setProductCode("ID_MIN")
                .setCertType("IDENTITY_CARD")
                .setCertName(certName)
                .setCertNo(certNo)
                .setFaceContrastPicture(base64Photo);

        try {
            ContrastFaceVerifyResponse response = callWithRetry(primaryEndpoint,
                    client -> client.contrastFaceVerify(request));
            return parseContrastResult(response);
        } catch (Exception e) {
            log.error("人脸比对异常: {}", e.getMessage(), e);
            throw new RuntimeException("人脸认证服务异常，请稍后重试", e);
        }
    }

    /**
     * 盲人二要素核验（身份证姓名+号码与公安库比对）
     */
    public boolean verifyIdCard(String certName, String certNo) {
        Id2MetaVerifyRequest request = new Id2MetaVerifyRequest()
                .setParamType("normal")
                .setUserName(certName)
                .setIdentifyNum(certNo);

        try {
            Id2MetaVerifyResponse response = callWithRetry(primaryEndpoint,
                    client -> client.id2MetaVerify(request));
            return parseId2MetaResult(response);
        } catch (Exception e) {
            log.error("二要素核验异常: {}", e.getMessage(), e);
            throw new RuntimeException("身份认证服务异常，请稍后重试", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private <R> R callWithRetry(String primary, ThrowingFunction<Client, R> action) throws Exception {
        try {
            Client client = createClient(primary);
            return action.apply(client);
        } catch (Exception e) {
            log.warn("主端点 {} 调用失败，尝试备用端点: {}", primary, e.getMessage());
            Client backup = createClient(BACKUP_ENDPOINT);
            return action.apply(backup);
        }
    }

    private Client createClient(String endpoint) throws Exception {
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setEndpoint(endpoint);
        return new Client(config);
    }

    private FaceVerifyResult parseContrastResult(ContrastFaceVerifyResponse response) {
        ContrastFaceVerifyResponseBody body = response.getBody();
        if (body == null || body.getResultObject() == null) {
            log.warn("人脸比对返回结果为空");
            return new FaceVerifyResult(false, "NO_RESULT", "认证结果为空");
        }

        var result = body.getResultObject();
        boolean passed = "T".equals(result.getPassed());
        String subCode = result.getSubCode();

        log.info("人脸比对结果: passed={}, subCode={}", passed, subCode);
        return new FaceVerifyResult(passed, subCode,
                passed ? "人脸比对通过" : describeSubCode(subCode));
    }

    private boolean parseId2MetaResult(Id2MetaVerifyResponse response) {
        Id2MetaVerifyResponseBody body = response.getBody();
        if (body == null || body.getResultObject() == null) {
            log.warn("二要素核验返回结果为空, code={}", body != null ? body.getCode() : "null");
            return false;
        }

        var result = body.getResultObject();
        boolean passed = "1".equals(result.getBizCode());
        log.info("二要素核验结果: bizCode={}, passed={}", result.getBizCode(), passed);
        return passed;
    }

    private Long parseSceneId() {
        if (sceneIdStr == null || sceneIdStr.isBlank()) {
            throw new IllegalStateException("未配置阿里云实人认证场景ID (aliyun.cloudauth.scene-id)");
        }
        return Long.parseLong(sceneIdStr);
    }

    private String describeSubCode(String subCode) {
        if (subCode == null) return "人脸比对未通过";
        return switch (subCode) {
            case "2001" -> "人脸比对不通过";
            case "2002" -> "人脸比对不通过（活体检测失败）";
            case "2003" -> "人脸比对不通过（身份证照片质量差）";
            case "2004" -> "人脸比对不通过（系统判定为翻拍）";
            case "2005" -> "人脸比对不通过（系统判定为PS）";
            default -> "人脸比对未通过（" + subCode + "）";
        };
    }

    private String generateOrderNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 人脸比对结果
     */
    public static class FaceVerifyResult {
        private final boolean passed;
        private final String subCode;
        private final String message;

        public FaceVerifyResult(boolean passed, String subCode, String message) {
            this.passed = passed;
            this.subCode = subCode;
            this.message = message;
        }

        public boolean isPassed() { return passed; }
        public String getSubCode() { return subCode; }
        public String getMessage() { return message; }
    }
}

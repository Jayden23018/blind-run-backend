package com.example.demo.service.impl;

import com.aliyun.cloudauth20190307.Client;
import com.aliyun.cloudauth20190307.models.DescribeFaceVerifyRequest;
import com.aliyun.cloudauth20190307.models.DescribeFaceVerifyResponse;
import com.aliyun.cloudauth20190307.models.DescribeFaceVerifyResponseBody;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyRequest;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponse;
import com.aliyun.cloudauth20190307.models.Id2MetaVerifyResponseBody;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyRequest;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyResponse;
import com.aliyun.cloudauth20190307.models.InitFaceVerifyResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.example.demo.service.FaceVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 阿里云金融级实人认证服务
 *
 * 志愿者端：InitFaceVerify / DescribeFaceVerify（动作活体，productCode=SMART）
 * 盲人端：Id2MetaVerify（二要素核验）
 * 主备端点自动切换（全局 → Beijing）
 *
 * 注意：本类不带 @ConditionalOnProperty，始终装配——盲人 Id2Meta 核验依赖本类，
 * 且志愿者 step1 的二要素核验也复用 {@link #verifyIdCard}。动作活体的 test stub
 * 单独走 {@code TestFaceVerifyServiceImpl}，二者通过 FaceVerifyService 接口切换。
 */
@Slf4j
@Service
public class AliyunIdVerifyService implements FaceVerifyService {

    @Value("${aliyun.credentials.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.credentials.access-key-secret:}")
    private String accessKeySecret;

    /** 动作活体 sceneId（SMART 方案，需在阿里云控制台单独新建） */
    @Value("${app.face-verify.scene-id:}")
    private String faceVerifySceneIdStr;

    @Value("${aliyun.cloudauth.endpoint:cloudauth.aliyuncs.com}")
    private String primaryEndpoint;

    private static final String BACKUP_ENDPOINT = "cloudauth.cn-beijing.aliyuncs.com";

    /**
     * 发起动作活体认证（InitFaceVerify，productCode=SMART）。
     * 返回 certifyId + 前端要打开的 CertifyUrl；失败时 certifyId/certifyUrl 为 null。
     */
    @Override
    public FaceVerifyInitResult initFaceVerify(String certName, String certNo, String metaInfo,
                                               String returnUrl, String outerOrderNo) {
        Long sceneId = parseFaceVerifySceneId();
        InitFaceVerifyRequest request = new InitFaceVerifyRequest()
                .setSceneId(sceneId)
                .setOuterOrderNo(outerOrderNo)
                .setProductCode("SMART")          // 动作活体方案
                .setCertType("IDENTITY_CARD")
                .setCertName(certName)
                .setCertNo(certNo)
                .setMetaInfo(metaInfo)
                .setReturnUrl(returnUrl);

        try {
            InitFaceVerifyResponse response = callWithRetry(primaryEndpoint,
                    client -> client.initFaceVerify(request));
            return parseInitResult(response);
        } catch (Exception e) {
            log.error("发起动作活体认证异常: {}", e.getMessage(), e);
            return new FaceVerifyInitResult(null, null, "认证服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 按 certifyId 拉取动作活体认证结果（DescribeFaceVerify）。
     */
    @Override
    public FaceVerifyResult describeFaceVerify(String certifyId) {
        Long sceneId = parseFaceVerifySceneId();
        DescribeFaceVerifyRequest request = new DescribeFaceVerifyRequest()
                .setSceneId(sceneId)
                .setCertifyId(certifyId);

        try {
            DescribeFaceVerifyResponse response = callWithRetry(primaryEndpoint,
                    client -> client.describeFaceVerify(request));
            return parseDescribeResult(response);
        } catch (Exception e) {
            log.error("查询动作活体认证结果异常: certifyId={}, {}", certifyId, e.getMessage(), e);
            return new FaceVerifyResult(false, "SERVICE_ERROR", "认证服务暂时不可用，请稍后重试");
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
            return false;
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
                .setEndpoint(endpoint)
                .setConnectTimeout(5000)   // 连接超时 5s，避免阿里云无响应时卡住线程
                .setReadTimeout(10000);    // 读取超时 10s
        return new Client(config);
    }

    private FaceVerifyInitResult parseInitResult(InitFaceVerifyResponse response) {
        InitFaceVerifyResponseBody body = response.getBody();
        if (body == null) {
            log.warn("InitFaceVerify 返回 body 为空");
            return new FaceVerifyInitResult(null, null, "认证服务无响应");
        }

        String code = body.getCode();
        if (!"200".equals(code)) {
            log.warn("InitFaceVerify API 返回错误: code={}, message={}", code, body.getMessage());
            return new FaceVerifyInitResult(null, null, describeInitErrorCode(code, body.getMessage()));
        }

        if (body.getResultObject() == null
                || body.getResultObject().getCertifyId() == null
                || body.getResultObject().getCertifyUrl() == null) {
            log.warn("InitFaceVerify ResultObject 缺失: code={}, message={}", code, body.getMessage());
            return new FaceVerifyInitResult(null, null, "认证服务返回不完整");
        }

        return new FaceVerifyInitResult(
                body.getResultObject().getCertifyId(),
                body.getResultObject().getCertifyUrl(),
                "已发起，请在前端完成动作活体");
    }

    private FaceVerifyResult parseDescribeResult(DescribeFaceVerifyResponse response) {
        DescribeFaceVerifyResponseBody body = response.getBody();
        if (body == null) {
            log.warn("DescribeFaceVerify 返回 body 为空");
            return new FaceVerifyResult(false, "NO_RESULT", "认证服务无响应");
        }

        String code = body.getCode();
        if (!"200".equals(code)) {
            log.warn("DescribeFaceVerify API 返回错误: code={}, message={}", code, body.getMessage());
            return new FaceVerifyResult(false, code, describeInitErrorCode(code, body.getMessage()));
        }

        if (body.getResultObject() == null) {
            log.warn("DescribeFaceVerify ResultObject 为空: code={}, message={}", code, body.getMessage());
            return new FaceVerifyResult(false, "NO_RESULT", "认证结果为空");
        }

        var result = body.getResultObject();
        boolean passed = "T".equals(result.getPassed());
        String subCode = result.getSubCode();

        log.info("动作活体认证结果: passed={}, subCode={}", passed, subCode);
        return new FaceVerifyResult(passed, subCode,
                passed ? "认证通过" : describeSubCode(subCode));
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

    private Long parseFaceVerifySceneId() {
        if (faceVerifySceneIdStr == null || faceVerifySceneIdStr.isBlank()) {
            throw new IllegalStateException("未配置动作活体认证场景ID (app.face-verify.scene-id)");
        }
        return Long.parseLong(faceVerifySceneIdStr);
    }

    private String describeInitErrorCode(String code, String message) {
        if (code == null) return "认证服务异常";
        return switch (code) {
            case "400" -> "请求参数不完整";
            case "401" -> "身份信息格式不正确";
            case "404" -> "认证场景未配置，请先在阿里云控制台创建动作活体认证场景";
            case "410" -> "未完成OSS授权";
            case "411" -> "RAM账号无权限";
            case "412" -> "服务欠费";
            case "417" -> "身份信息比对源不可用";
            case "500" -> "阿里云系统内部错误";
            default -> "认证失败（" + code + ": " + message + "）";
        };
    }

    private String describeSubCode(String subCode) {
        if (subCode == null) return "认证未通过";
        return switch (subCode) {
            case "200" -> "认证通过";
            case "201" -> "姓名和身份证号码不一致";
            case "202" -> "查询不到身份信息";
            case "203" -> "查询不到照片或照片不可用";
            case "204" -> "人脸比对不一致";
            case "205" -> "活体检测存在风险";
            case "206" -> "业务策略限制";
            case "209" -> "权威比对源异常";
            case "401" -> "认证不完整，请重新完成动作活体";
            default -> "认证未通过（" + subCode + "）";
        };
    }
}

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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.UUID;

/**
 * 阿里云金融级实人认证服务
 *
 * 志愿者端：ContrastFaceVerify（照片比对）
 * 盲人端：Id2MetaVerify（二要素核验）
 * 主备端点自动切换（全局 → Beijing）
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

    @Value("${aliyun.cloudauth.endpoint:cloudauth.aliyuncs.com}")
    private String primaryEndpoint;

    private static final String BACKUP_ENDPOINT = "cloudauth.cn-beijing.aliyuncs.com";
    private static final int MAX_PHOTO_SIZE_BYTES = 900 * 1024; // 900KB（留余量，阿里云限制1MB）
    private static final int MAX_PHOTO_DIMENSION = 1280; // 短边不超过1280px

    /**
     * 志愿者人脸照片比对
     */
    public FaceVerifyResult contrastFaceVerify(String certName, String certNo, byte[] facePhoto) {
        String orderNo = generateOrderNo();
        byte[] compressedPhoto = compressPhoto(facePhoto);
        String base64Photo = Base64.getEncoder().encodeToString(compressedPhoto);
        Long sceneId = parseSceneId();

        ContrastFaceVerifyRequest request = new ContrastFaceVerifyRequest()
                .setSceneId(sceneId)
                .setOuterOrderNo(orderNo)
                .setProductCode("ID_MIN")
                .setModel("NO_LIVENESS")
                .setCertType("IDENTITY_CARD")
                .setCertName(certName)
                .setCertNo(certNo)
                .setFaceContrastPicture(base64Photo)
                .setCrop("T");

        try {
            ContrastFaceVerifyResponse response = callWithRetry(primaryEndpoint,
                    client -> client.contrastFaceVerify(request));
            return parseContrastResult(response);
        } catch (Exception e) {
            log.error("人脸比对异常: {}", e.getMessage(), e);
            return new FaceVerifyResult(false, "SERVICE_ERROR", "人脸认证服务暂时不可用，请稍后重试");
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

    private FaceVerifyResult parseContrastResult(ContrastFaceVerifyResponse response) {
        ContrastFaceVerifyResponseBody body = response.getBody();
        if (body == null) {
            log.warn("人脸比对返回body为空");
            return new FaceVerifyResult(false, "NO_RESULT", "认证服务无响应");
        }

        String code = body.getCode();
        if (!"200".equals(code)) {
            String errorMsg = describeErrorCode(code, body.getMessage());
            log.warn("人脸比对API返回错误: code={}, message={}", code, body.getMessage());
            return new FaceVerifyResult(false, code, errorMsg);
        }

        if (body.getResultObject() == null) {
            log.warn("人脸比对ResultObject为空, code={}, message={}", code, body.getMessage());
            return new FaceVerifyResult(false, "NO_RESULT", "认证结果为空");
        }

        var result = body.getResultObject();
        boolean passed = "T".equals(result.getPassed());
        String subCode = result.getSubCode();

        log.info("人脸比对结果: passed={}, subCode={}", passed, subCode);
        return new FaceVerifyResult(passed, subCode,
                passed ? "人脸比对通过" : describeSubCode(subCode));
    }

    private String describeErrorCode(String code, String message) {
        if (code == null) return "认证服务异常";
        return switch (code) {
            case "400" -> "请求参数不完整";
            case "401" -> "身份信息格式不正确";
            case "404" -> "认证场景未配置，请先在阿里云控制台创建认证场景";
            case "410" -> "未完成OSS授权";
            case "411" -> "RAM账号无权限";
            case "412" -> "服务欠费";
            case "417" -> "身份信息比对源不可用";
            case "419" -> "上传图片不可用，请更换清晰的正面人脸照片";
            case "421" -> "上传图片过大（超过1MB），请压缩后重试";
            case "500" -> "阿里云系统内部错误";
            default -> "认证失败（" + code + ": " + message + "）";
        };
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
            case "200" -> "认证通过";
            case "201" -> "姓名和身份证号码不一致";
            case "202" -> "查询不到身份信息";
            case "203" -> "查询不到照片或照片不可用";
            case "204" -> "人脸比对不一致";
            case "205" -> "活体检测存在风险";
            case "206" -> "业务策略限制";
            case "209" -> "权威比对源异常";
            default -> "人脸比对未通过（" + subCode + "）";
        };
    }

    /**
     * 压缩人脸照片：缩小尺寸 + 降低质量，确保不超过阿里云1MB限制
     */
    private byte[] compressPhoto(byte[] photoBytes) {
        try {
            if (photoBytes.length <= MAX_PHOTO_SIZE_BYTES) {
                return photoBytes;
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(photoBytes));
            if (image == null) {
                log.warn("无法解析图片，使用原始数据");
                return photoBytes;
            }

            // 按比例缩小，短边不超过 MAX_PHOTO_DIMENSION
            int width = image.getWidth();
            int height = image.getHeight();
            int shortSide = Math.min(width, height);
            if (shortSide > MAX_PHOTO_DIMENSION) {
                double scale = (double) MAX_PHOTO_DIMENSION / shortSide;
                width = (int) (width * scale);
                height = (int) (height * scale);
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, width, height, null);
                g.dispose();
                image = scaled;
            }

            // 逐步降低JPEG质量直到满足大小限制
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return photoBytes;
            }
            ImageWriter writer = writers.next();

            for (float quality = 0.85f; quality >= 0.3f; quality -= 0.15f) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.setOutput(ImageIO.createImageOutputStream(baos));
                writer.write(null, new IIOImage(image, null, null), param);
                byte[] result = baos.toByteArray();
                if (result.length <= MAX_PHOTO_SIZE_BYTES) {
                    log.info("照片压缩: {}KB → {}KB (quality={})",
                            photoBytes.length / 1024, result.length / 1024, quality);
                    return result;
                }
            }

            log.warn("照片压缩后仍超过限制，使用最终结果");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.3f);
            writer.setOutput(ImageIO.createImageOutputStream(baos));
            writer.write(null, new IIOImage(image, null, null), param);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("照片压缩失败，使用原始数据: {}", e.getMessage());
            return photoBytes;
        }
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

package com.example.demo.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 阿里云 OSS 文件存储实现（私有 Bucket + 签名 URL）
 * 通过 app.storage.type=oss 激活
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "oss")
public class OssFileStorageService implements FileStorageService {

    /** 签名 URL 有效期：1 小时 */
    private static final long SIGNED_URL_EXPIRE_MS = 3600 * 1000L;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".pdf"
    );

    private static final Map<String, String> CONTENT_TYPE_MAP = Map.ofEntries(
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".png", "image/png"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".bmp", "image/bmp"),
            Map.entry(".pdf", "application/pdf")
    );

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        log.info("OSS 客户端初始化成功 | endpoint={}, bucket={}", endpoint, bucketName);
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS 客户端已关闭");
        }
    }

    @Override
    public String store(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        String extension = extractAndValidateExtension(originalName);
        String objectName = "uploads/" + UUID.randomUUID() + extension;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(CONTENT_TYPE_MAP.getOrDefault(extension, "application/octet-stream"));

        try (InputStream inputStream = file.getInputStream()) {
            ossClient.putObject(bucketName, objectName, inputStream, metadata);
        } catch (IOException e) {
            throw new RuntimeException("文件上传到 OSS 失败", e);
        }

        log.info("文件已上传到 OSS: {}", objectName);
        return objectName;
    }

    @Override
    public String getUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        // 已经是 http/https 开头的完整 URL（兼容旧数据），直接返回
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        Date expiration = new Date(System.currentTimeMillis() + SIGNED_URL_EXPIRE_MS);
        return ossClient.generatePresignedUrl(bucketName, key, expiration).toString();
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank() || key.startsWith("http://") || key.startsWith("https://")) {
            return;
        }
        try {
            ossClient.deleteObject(bucketName, key);
        } catch (Exception e) {
            log.warn("OSS 文件删除失败: {}", key, e);
        }
    }

    private String extractAndValidateExtension(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            throw new IllegalArgumentException("文件必须包含扩展名");
        }
        String extension = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型，仅允许: " + ALLOWED_EXTENSIONS);
        }
        return extension;
    }
}

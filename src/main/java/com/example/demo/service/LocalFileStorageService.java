package com.example.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储实现 —— v1.0 保存到本地 uploads/ 目录
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    /** 允许上传的文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".pdf"
    );

    @Value("${app.upload.dir:uploads/}")
    private String uploadDir;

    @Override
    public String store(MultipartFile file) {
        try {
            Path dir = Paths.get(uploadDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            String originalName = file.getOriginalFilename();
            String extension = extractAndValidateExtension(originalName);
            String filename = UUID.randomUUID() + extension;

            Path target = dir.resolve(filename).normalize();

            // 确保目标路径在上传目录内（防止路径穿越）
            if (!target.startsWith(dir.normalize())) {
                throw new IllegalArgumentException("非法文件路径");
            }

            file.transferTo(target.toFile());

            log.info("文件已保存: {}", filename);
            return uploadDir + filename;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败", e);
        }
    }

    /**
     * 提取并验证文件扩展名
     * @throws IllegalArgumentException 如果扩展名不在白名单中
     */
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

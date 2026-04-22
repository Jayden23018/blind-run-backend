package com.example.demo.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口 —— 支持 local（本地磁盘）和 oss（阿里云 OSS）两种实现
 */
public interface FileStorageService {
    /**
     * 存储文件，返回文件 key（OSS 为 object key，local 为本地路径）
     */
    String store(MultipartFile file);

    /**
     * 根据文件 key 生成可访问的 URL（OSS 返回签名 URL，local 原样返回）
     */
    String getUrl(String key);
}

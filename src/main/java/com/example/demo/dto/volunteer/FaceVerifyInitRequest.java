package com.example.demo.dto.volunteer;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起动作活体认证请求。
 * metaInfo 是前端用阿里云 JS SDK 采集的设备指纹（JSON 字符串）。
 */
@Data
public class FaceVerifyInitRequest {

    @NotBlank(message = "metaInfo 不能为空")
    private String metaInfo;
}

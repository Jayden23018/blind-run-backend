package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发起动作活体认证响应。
 * status: PENDING（已发起，等待前端完成动作活体）/ ERROR（发起失败）
 */
@Data
@AllArgsConstructor
public class FaceVerifyInitResponse {
    private String certifyId;
    private String certifyUrl;
    private String status;
    private String message;
}

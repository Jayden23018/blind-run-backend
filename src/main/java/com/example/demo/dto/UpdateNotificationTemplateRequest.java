package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新通知模板请求 DTO
 */
@Data
public class UpdateNotificationTemplateRequest {

    @NotBlank(message = "模板文案不能为空")
    private String templateText;

    private String ttsText;
}

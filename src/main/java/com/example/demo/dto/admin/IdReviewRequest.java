package com.example.demo.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 身份证审核请求 DTO
 */
@Data
public class IdReviewRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "审核结果不能为空")
    private Boolean approved;

    private String rejectionReason; // approved=false 时必填
}

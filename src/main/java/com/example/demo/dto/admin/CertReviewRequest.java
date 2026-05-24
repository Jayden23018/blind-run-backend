package com.example.demo.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 资质证书审核请求 DTO
 */
@Data
public class CertReviewRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "审核结果不能为空")
    private Boolean approved;

    /** approved=false 时建议填写原因 */
    private String rejectionReason;
}

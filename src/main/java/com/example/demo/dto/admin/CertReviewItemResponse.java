package com.example.demo.dto.admin;

import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.VerificationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资质证书待审核项响应 DTO
 */
@Data
@AllArgsConstructor
public class CertReviewItemResponse {
    private Long userId;
    private String name;
    private String phone;           // 已脱敏
    private String verificationDocUrl; // 预签名 URL
    private VerificationStatus verificationStatus;
    private RegistrationStep registrationStep;
    private LocalDateTime updatedAt;
}

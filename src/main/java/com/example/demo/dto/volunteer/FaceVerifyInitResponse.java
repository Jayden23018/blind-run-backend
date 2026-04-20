package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 人脸验证响应 DTO
 */
@Data
@AllArgsConstructor
public class FaceVerifyInitResponse {
    private boolean passed;
    private String status;   // "PASSED" / "REJECTED"
    private String message;
}

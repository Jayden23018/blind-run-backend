package com.example.demo.dto.admin;

import com.example.demo.entity.FaceVerifyStatus;
import com.example.demo.entity.IdVerifyStatus;
import com.example.demo.entity.RegistrationStep;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者审核项响应 DTO
 */
@Data
@AllArgsConstructor
public class VolunteerReviewItemResponse {
    private Long userId;
    private String name;
    private String phone;
    private String idCardNumber; // 脱敏显示
    private String idCardName;
    private String idCardFrontUrl;
    private String idCardBackUrl;
    private String facePhotoUrl;
    private RegistrationStep registrationStep;
    private IdVerifyStatus idVerifyStatus;
    private FaceVerifyStatus faceVerifyStatus;
    private LocalDateTime updatedAt;
}

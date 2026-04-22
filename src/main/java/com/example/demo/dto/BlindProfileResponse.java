package com.example.demo.dto;

import com.example.demo.entity.*;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 盲人资料响应 DTO
 * 注意：紧急联系人已迁移到独立的 EmergencyContact 接口
 */
@Data
@AllArgsConstructor
public class BlindProfileResponse {
    private String name;
    private String runningPace;
    private String specialNeeds;
    private BlindVerifyStatus verifyStatus;

    private VisionLevel visionLevel;
    private Boolean hasGuideDog;
    private TetherPreference tetherPreference;
    private ChatPreference chatPreference;
    private PacePreference defaultPace;
}

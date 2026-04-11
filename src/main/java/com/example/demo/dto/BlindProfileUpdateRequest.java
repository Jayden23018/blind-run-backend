package com.example.demo.dto;

import lombok.Data;

/**
 * 盲人资料更新请求 DTO
 * 注意：紧急联系人已迁移到独立的 EmergencyContact 接口
 */
@Data
public class BlindProfileUpdateRequest {
    private String name;
    private String runningPace;
    private String specialNeeds;
}

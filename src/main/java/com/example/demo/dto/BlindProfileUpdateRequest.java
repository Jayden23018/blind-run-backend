package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 盲人资料更新请求 DTO
 * 注意：紧急联系人已迁移到独立的 EmergencyContact 接口
 */
@Data
public class BlindProfileUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 50, message = "姓名长度必须在2-50个字符之间")
    private String name;

    @Size(max = 20, message = "配速描述不能超过20个字符")
    private String runningPace;

    @Size(max = 500, message = "特殊需求不能超过500个字符")
    private String specialNeeds;
}

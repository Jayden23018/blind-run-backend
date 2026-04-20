package com.example.demo.dto.volunteer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 志愿者基本信息请求 DTO
 */
@Data
public class BasicInfoRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 50, message = "姓名长度必须在2-50个字符之间")
    private String name;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 跑步经验 */
    private String runningExperience;

    /** 是否有陪跑经验 */
    private Boolean hasGuidedBefore;

    /** 紧急情况处理经验 */
    private String emergencyExperience;
}

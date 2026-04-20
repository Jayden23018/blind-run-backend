package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 盲人用户身份认证请求 DTO
 */
@Data
public class BlindVerifyRequest {

    @NotBlank(message = "身份证姓名不能为空")
    @Size(min = 2, max = 50, message = "身份证姓名长度必须在2-50个字符之间")
    private String idCardName;

    @NotBlank(message = "身份证号码不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号码格式不正确")
    private String idCardNumber;
}

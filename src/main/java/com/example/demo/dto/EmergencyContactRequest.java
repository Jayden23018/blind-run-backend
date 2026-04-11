package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 紧急联系人创建/更新请求
 */
@Data
public class EmergencyContactRequest {

    @NotBlank(message = "联系人姓名不能为空")
    @Size(max = 50)
    private String name;

    @NotBlank(message = "联系人电话不能为空")
    @Size(max = 20)
    private String phone;

    @Size(max = 20)
    private String relationship;

    /** 是否设为主要联系人 */
    private Boolean isPrimary = false;
}

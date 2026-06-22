package com.example.demo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 紧急联系人创建/更新请求
 *
 * 【PATCH 语义】用于 PUT 更新时，未传（null）的字段保留原值，
 * 因此 name/phone 不加 @NotBlank（避免未传时直接 400）。
 * 新增场景的必填校验由 EmergencyContactService.addContact 手动完成。
 */
@Data
public class EmergencyContactRequest {

    @Size(max = 50)
    private String name;

    @Size(max = 20)
    private String phone;

    @Size(max = 20)
    private String relationship;

    /** 是否设为主要联系人 */
    private Boolean isPrimary = false;
}

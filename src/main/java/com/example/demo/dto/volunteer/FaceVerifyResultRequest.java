package com.example.demo.dto.volunteer;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 查询动作活体认证结果请求。
 * certifyId 来自 init 接口的返回，须与当前用户绑定的 certifyId 一致（防越权）。
 */
@Data
public class FaceVerifyResultRequest {

    @NotBlank(message = "certifyId 不能为空")
    private String certifyId;
}

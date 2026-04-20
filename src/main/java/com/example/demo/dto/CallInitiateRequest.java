package com.example.demo.dto;

import com.example.demo.entity.CallRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起通话请求
 */
@Data
public class CallInitiateRequest {

    /** 发起方角色：BLIND_USER 或 VOLUNTEER */
    @NotNull(message = "发起方角色不能为空")
    private CallRole callerRole;
}

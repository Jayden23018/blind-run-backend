package com.example.demo.dto;

import lombok.Data;

/**
 * 发起通话请求
 */
@Data
public class CallInitiateRequest {

    /** 发起方角色：BLIND_USER 或 VOLUNTEER */
    private String callerRole;
}

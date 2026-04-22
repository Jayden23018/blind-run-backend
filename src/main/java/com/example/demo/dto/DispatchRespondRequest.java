package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 志愿者响应派单请求 DTO
 */
public record DispatchRespondRequest(
        @NotNull(message = "action 不能为空")
        RespondAction action
) {}

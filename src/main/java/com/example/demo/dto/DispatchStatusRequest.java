package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;

public record DispatchStatusRequest(
        @NotNull(message = "wantsDispatch 不能为空")
        Boolean wantsDispatch
) {}

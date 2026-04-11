package com.example.demo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 盲人位置上报请求
 */
@Data
public class BlindLocationRequest {

    @NotNull(message = "纬度不能为空")
    private Double latitude;

    @NotNull(message = "经度不能为空")
    private Double longitude;
}

package com.example.demo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 紧急事件触发请求
 */
@Data
public class EmergencyTriggerRequest {

    /** 订单ID（可选：进行中订单触发时传入以校验参与者；独立 SOS 不传） */
    private Long orderId;

    /** 当前纬度（可选，盲人端 GPS） */
    @DecimalMin(value = "-90", message = "纬度不能小于-90")
    @DecimalMax(value = "90", message = "纬度不能大于90")
    private BigDecimal gpsLat;

    /** 当前经度（可选，盲人端 GPS） */
    @DecimalMin(value = "-180", message = "经度不能小于-180")
    @DecimalMax(value = "180", message = "经度不能大于180")
    private BigDecimal gpsLng;
}

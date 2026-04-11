package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 紧急事件触发请求
 */
@Data
public class EmergencyTriggerRequest {

    /** 订单ID */
    private Long orderId;

    /** 当前纬度（可选，盲人端 GPS） */
    private BigDecimal gpsLat;

    /** 当前经度（可选，盲人端 GPS） */
    private BigDecimal gpsLng;
}

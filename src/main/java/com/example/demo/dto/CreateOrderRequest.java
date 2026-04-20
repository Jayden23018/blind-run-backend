package com.example.demo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建订单请求 DTO —— 盲人用户下单时提交的数据
 *
 * 【字段说明】
 * startLatitude   起跑点纬度（用于距离匹配计算）
 * startLongitude  起跑点经度
 * startAddress    起跑点文字描述（如"朝阳公园南门"，方便志愿者看懂）
 * plannedStartTime 计划开始跑步的时间
 * plannedEndTime   计划结束跑步的时间
 */
@Data
public class CreateOrderRequest {

    /** 起跑点纬度 */
    @NotNull(message = "起跑点纬度不能为空")
    @DecimalMin(value = "-90", message = "纬度不能小于-90")
    @DecimalMax(value = "90", message = "纬度不能大于90")
    private Double startLatitude;

    /** 起跑点经度 */
    @NotNull(message = "起跑点经度不能为空")
    @DecimalMin(value = "-180", message = "经度不能小于-180")
    @DecimalMax(value = "180", message = "经度不能大于180")
    private Double startLongitude;

    /** 起跑点文字描述 */
    @NotNull(message = "起跑点地址不能为空")
    private String startAddress;

    /** 计划开始时间 */
    @NotNull(message = "计划开始时间不能为空")
    private LocalDateTime plannedStartTime;

    /** 计划结束时间 */
    @NotNull(message = "计划结束时间不能为空")
    private LocalDateTime plannedEndTime;
}

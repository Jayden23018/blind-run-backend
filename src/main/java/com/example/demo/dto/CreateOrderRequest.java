package com.example.demo.dto;

import com.example.demo.entity.PacePreference;
import com.example.demo.entity.RoutePreference;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建订单请求 DTO —— 盲人用户下单时提交的数据
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

    /** 预计跑步时长（分钟） */
    @Min(value = 10, message = "预计时长不能少于10分钟")
    @Max(value = 300, message = "预计时长不能超过300分钟")
    private Integer expectedDurationMinutes;

    /** 本次配速偏好（覆盖档案默认值） */
    private PacePreference pacePreference;

    /** 路线偏好 */
    private RoutePreference routePreference;

    /** 路线备注 */
    @Size(max = 200, message = "路线备注不能超过200个字符")
    private String routeNotes;

    /** 本次是否携带导盲犬（null=使用档案默认值） */
    private Boolean hasGuideDogThisRun;

    /** 本次订单一次性备注 */
    @Size(max = 200, message = "备注不能超过200个字符")
    private String specialNotes;
}

package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者首页聚合接口 —— 当前活跃订单精简视图
 *
 * 对应 status ∈ {IN_PROGRESS, DRIVER_EN_ROUTE, DRIVER_ARRIVED} 的订单（通常 ≤1 条）。
 * 不直接返回 RunOrder 实体，盲人手机号一律脱敏。
 */
@Data
@AllArgsConstructor
public class VolunteerDispatchActiveOrder {
    /** 订单 ID */
    private Long orderId;
    /** 订单状态（枚举名，如 IN_PROGRESS / DRIVER_EN_ROUTE / DRIVER_ARRIVED） */
    private String status;
    /** 计划开始时间 */
    private LocalDateTime plannedStartTime;
    /** 计划结束时间 */
    private LocalDateTime plannedEndTime;
    /** 起跑点文字地址（如「朝阳公园南门」） */
    private String startAddress;
    /** 盲人姓名 */
    private String blindName;
    /** 盲人手机号（已脱敏，如 138****0001） */
    private String blindPhoneMasked;
    /** 志愿者接单时间（接单后才有值） */
    private LocalDateTime acceptedAt;
}

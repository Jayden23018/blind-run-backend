package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者首页聚合接口 —— 近期服务记录精简视图
 *
 * 取最近 5 条订单（按创建时间倒序），用于首页「近期服务记录」列表预览。
 * 详情页应另调专门的订单详情/列表分页接口。
 */
@Data
@AllArgsConstructor
public class VolunteerDispatchRecentOrder {
    /** 订单 ID */
    private Long orderId;
    /** 订单状态（枚举名，如 COMPLETED / CANCELLED） */
    private String status;
    /** 计划开始时间 */
    private LocalDateTime plannedStartTime;
    /** 订单完成时间（仅 status=COMPLETED 有值，否则 null） */
    private LocalDateTime completedAt;
    /** 该订单志愿者收到的评分（1-5），无评价为 null */
    private Integer rating;
}

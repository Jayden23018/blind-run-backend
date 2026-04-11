package com.example.demo.entity;

/**
 * 订单状态枚举 —— 定义订单在整个生命周期中的状态
 *
 * 【状态流转】
 * PENDING_MATCH   → PENDING_ACCEPT   （系统匹配成功）
 * PENDING_MATCH   → CANCELLED        （盲人取消）
 * PENDING_ACCEPT  → IN_PROGRESS      （志愿者接单）
 * PENDING_ACCEPT  → REMATCHING       （志愿者取消，重新匹配）
 * PENDING_ACCEPT  → CANCELLED        （盲人取消）
 * IN_PROGRESS     → DRIVER_EN_ROUTE  （志愿者确认出发）
 * IN_PROGRESS     → COMPLETED        （志愿者点击结束 或 系统超时自动完成）
 * IN_PROGRESS     → CANCELLED        （志愿者取消，记录为爽约）
 * DRIVER_EN_ROUTE → DRIVER_ARRIVED   （志愿者确认到达）
 * DRIVER_EN_ROUTE → REMATCHING       （志愿者取消，重新匹配）
 * DRIVER_ARRIVED  → REMATCHING       （志愿者取消，重新匹配）
 * DRIVER_EN_ROUTE → CANCELLED        （取消）
 * DRIVER_ARRIVED  → CANCELLED        （取消）
 * REMATCHING      → PENDING_ACCEPT   （新志愿者被推送，等待接单）
 * REMATCHING      → IN_PROGRESS      （新志愿者直接接单）
 * REMATCHING      → CANCELLED        （盲人取消）
 * REMATCHING      → NO_VOLUNTEER     （超时无人接单 — 预留终态）
 */
public enum OrderStatus {
    PENDING_MATCH,      // 待匹配：订单刚创建，等待系统匹配志愿者
    PENDING_ACCEPT,     // 待接受：已推送给志愿者，等待志愿者接受
    IN_PROGRESS,        // 进行中：志愿者已接单，服务进行中
    DRIVER_EN_ROUTE,    // 志愿者已出发
    DRIVER_ARRIVED,     // 志愿者已到达
    COMPLETED,          // 已完成：服务已完成
    CANCELLED,          // 已取消（盲人主动取消 / 志愿者在 IN_PROGRESS 阶段取消）
    REMATCHING,         // 重新匹配中：志愿者取消后等待新志愿者
    NO_VOLUNTEER        // 超时无人接单（预留终态）
}

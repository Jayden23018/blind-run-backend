package com.example.demo.dto;

import com.example.demo.entity.OrderStatus;

import java.util.List;

/** 订单轨迹回放：订单状态（用于区分"未到陪跑阶段"/"进行中数据不足"/"历史订单不支持"） + 双方各一条轨迹 + 各自统计 */
public record OrderTrackResponse(
        OrderStatus status,
        List<TrackPointDto> volunteerTrack,
        TrackStatsDto volunteerStats,
        List<TrackPointDto> blindTrack,
        TrackStatsDto blindStats) {
}

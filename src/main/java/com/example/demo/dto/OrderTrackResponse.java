package com.example.demo.dto;

import java.util.List;

/** 订单轨迹回放：双方各一条轨迹 + 各自统计 */
public record OrderTrackResponse(
        List<TrackPointDto> volunteerTrack,
        TrackStatsDto volunteerStats,
        List<TrackPointDto> blindTrack,
        TrackStatsDto blindStats) {
}

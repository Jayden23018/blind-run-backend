package com.example.demo.dto;

/** 轨迹统计：总里程（米）/ 耗时（秒）/ 平均配速（秒/公里，无里程时为 null） */
public record TrackStatsDto(double distanceMeters, long durationSeconds, Double avgPaceSecPerKm) {

    public static final TrackStatsDto EMPTY = new TrackStatsDto(0, 0, null);
}

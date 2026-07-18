package com.example.demo.dto;

import java.time.LocalDateTime;

/** 单个轨迹点 */
public record TrackPointDto(double lat, double lng, LocalDateTime recordedAt) {
}

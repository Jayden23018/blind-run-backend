package com.example.demo.dto;

import com.example.demo.entity.*;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单详情响应 DTO —— 查询单个订单时返回
 */
@Data
@AllArgsConstructor
public class OrderDetailResponse {
    private Long orderId;
    private OrderStatus status;
    private String startAddress;
    private Double startLatitude;
    private Double startLongitude;
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;
    private String volunteerPhone;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;

    // 订单级特殊需求
    private Integer expectedDurationMinutes;
    private PacePreference pacePreference;
    private RoutePreference routePreference;
    private String routeNotes;
    private Boolean hasGuideDogThisRun;
    private String specialNotes;

    // 盲人档案冗余（方便志愿者一次性查看）
    private VisionLevel visionLevel;
    private TetherPreference tetherPreference;
    private ChatPreference chatPreference;
}

package com.example.demo.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态日志响应
 */
@Data
public class OrderStatusLogResponse {
    private Long id;
    private Long orderId;
    private String fromStatus;
    private String toStatus;
    private Long changedBy;
    private LocalDateTime changedAt;
    private String remark;
}

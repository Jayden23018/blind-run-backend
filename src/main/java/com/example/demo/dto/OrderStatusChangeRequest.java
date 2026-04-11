package com.example.demo.dto;

import lombok.Data;

/**
 * 订单状态变更请求
 */
@Data
public class OrderStatusChangeRequest {

    /** 目标状态 */
    private String status;

    /** 备注 */
    private String remark;
}

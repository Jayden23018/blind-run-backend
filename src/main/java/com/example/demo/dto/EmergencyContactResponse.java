package com.example.demo.dto;

import lombok.Data;

/**
 * 紧急联系人响应
 */
@Data
public class EmergencyContactResponse {
    private Long id;
    private String name;
    private String phone;
    private String relationship;
    private Boolean isPrimary;
}

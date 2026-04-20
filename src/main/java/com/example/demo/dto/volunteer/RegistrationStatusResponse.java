package com.example.demo.dto.volunteer;

import com.example.demo.entity.RegistrationStep;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 注册状态响应 DTO
 */
@Data
@AllArgsConstructor
public class RegistrationStatusResponse {
    private RegistrationStep currentStep;
    private Boolean canAcceptOrders;
    private Map<String, Object> stepDetails;
}

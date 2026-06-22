package com.example.demo.dto;

import com.example.demo.entity.PacePreference;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 志愿者资料响应 DTO
 */
@Data
@AllArgsConstructor
public class VolunteerProfileResponse {
    private String name;
    private String verificationStatus;
    private List<VolunteerAvailableTimeSlot> availableTimeSlots;
    private Boolean acceptsGuideDog;
    private PacePreference paceRange;
    /** 是否开启接单（可服务状态）。前端用于展示开关状态 */
    private Boolean wantsDispatch;
}

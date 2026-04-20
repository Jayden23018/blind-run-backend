package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 志愿者资料更新请求 DTO
 */
@Data
public class VolunteerProfileUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 50, message = "姓名长度必须在2-50个字符之间")
    private String name;

    @Valid
    private List<VolunteerAvailableTimeSlot> availableTimeSlots;
}

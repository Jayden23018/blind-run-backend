package com.example.demo.dto;

import com.example.demo.entity.PacePreference;
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

    /** 是否接受携带导盲犬的订单 */
    private Boolean acceptsGuideDog;

    /** 志愿者可适应的配速范围 */
    private PacePreference paceRange;

    /**
     * 是否开启接单（可服务状态）。可选，PATCH 语义：未传保留原值。
     * false 时志愿者可浏览订单但不能接单。
     */
    private Boolean wantsDispatch;
}

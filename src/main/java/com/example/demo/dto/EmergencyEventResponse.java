package com.example.demo.dto;

import com.example.demo.entity.EmergencyEvent;
import com.example.demo.entity.EmergencyStatus;
import com.example.demo.entity.TriggerType;
import com.example.demo.entity.VolunteerAction;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 紧急事件响应 DTO —— 默认过滤敏感字段，不暴露 GPS 坐标原始值；
 * CS_ADMIN 可见 gpsLat/gpsLng 原始坐标（见 CsController）
 */
@Data
public class EmergencyEventResponse {

    private Long id;
    private Long orderId;
    private Long userId;
    private LocalDateTime triggeredAt;
    private TriggerType triggerType;
    private EmergencyStatus status;

    /** GPS 位置是否已提供（不暴露具体坐标） */
    private Boolean hasGpsLocation;

    /** 原始 GPS 坐标，仅 CS_ADMIN 可见（见 from(event, includeRawGps)） */
    private BigDecimal gpsLat;
    private BigDecimal gpsLng;

    private LocalDateTime volunteerNotifiedAt;
    private LocalDateTime volunteerConfirmedAt;
    private VolunteerAction volunteerAction;
    private LocalDateTime volunteerTimeoutAt;
    private Long csUserId;
    private String csNotes;
    private LocalDateTime resolvedAt;

    /**
     * 从实体转换为响应 DTO（过滤 GPS 坐标等敏感字段）
     */
    public static EmergencyEventResponse from(EmergencyEvent event) {
        return from(event, false);
    }

    /**
     * 从实体转换为响应 DTO
     * @param includeRawGps 是否附带原始 GPS 坐标（仅 CS_ADMIN）
     */
    public static EmergencyEventResponse from(EmergencyEvent event, boolean includeRawGps) {
        EmergencyEventResponse resp = new EmergencyEventResponse();
        resp.setId(event.getId());
        resp.setOrderId(event.getOrderId());
        resp.setUserId(event.getUserId());
        resp.setTriggeredAt(event.getTriggeredAt());
        resp.setTriggerType(event.getTriggerType());
        resp.setStatus(event.getStatus());
        resp.setHasGpsLocation(event.getGpsLat() != null && event.getGpsLng() != null);
        if (includeRawGps) {
            resp.setGpsLat(event.getGpsLat());
            resp.setGpsLng(event.getGpsLng());
        }
        resp.setVolunteerNotifiedAt(event.getVolunteerNotifiedAt());
        resp.setVolunteerConfirmedAt(event.getVolunteerConfirmedAt());
        resp.setVolunteerAction(event.getVolunteerAction());
        resp.setVolunteerTimeoutAt(event.getVolunteerTimeoutAt());
        resp.setCsUserId(event.getCsUserId());
        resp.setCsNotes(event.getCsNotes());
        resp.setResolvedAt(event.getResolvedAt());
        return resp;
    }
}

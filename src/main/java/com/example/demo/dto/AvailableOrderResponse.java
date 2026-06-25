package com.example.demo.dto;

import com.example.demo.entity.PacePreference;

import java.time.LocalDateTime;

public record AvailableOrderResponse(
        Long orderId,
        String startAddress,
        double distanceKm,
        LocalDateTime plannedStart,
        LocalDateTime plannedEnd,
        String blindUserPhone,
        Integer expectedDurationMinutes,
        PacePreference pacePreference,
        Boolean hasGuideDogThisRun,
        String specialNotes,
        boolean needsHelp) {}

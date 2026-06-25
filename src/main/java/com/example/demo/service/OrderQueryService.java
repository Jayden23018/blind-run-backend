package com.example.demo.service;

import com.example.demo.dto.AvailableOrderResponse;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.util.GeoUtils;
import com.example.demo.util.PhoneMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OrderQueryService {

    private final RunOrderRepository runOrderRepository;

    @Value("${app.matching.max-distance-km:10}")
    private double maxDistanceKm;

    public OrderQueryService(RunOrderRepository runOrderRepository) {
        this.runOrderRepository = runOrderRepository;
    }

    public RunOrder getOrder(Long orderId, Long userId) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(orderId)
                .orElseThrow(() -> new OrderNotFoundException("订单不存在，ID: " + orderId));

        boolean isBlind = order.getBlindUser().getId().equals(userId);
        boolean isVolunteer = order.getVolunteer() != null && order.getVolunteer().getId().equals(userId);

        if (!isBlind && !isVolunteer) {
            throw new OrderPermissionException("您无权查看此订单");
        }

        return order;
    }

    public List<AvailableOrderResponse> getAvailableOrders(Long volunteerId, double volunteerLat, double volunteerLng) {
        List<AvailableOrderResponse> result = new ArrayList<>();

        // 1. 附近常规订单（地理范围内）
        List<RunOrder> pendingOrders = runOrderRepository.findByStatusIn(
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.REMATCHING));

        for (RunOrder order : pendingOrders) {
            double distance = GeoUtils.distanceKm(
                    volunteerLat, volunteerLng,
                    order.getStartLatitude(), order.getStartLongitude()
            );
            if (distance <= maxDistanceKm) {
                result.add(new AvailableOrderResponse(
                        order.getId(),
                        order.getStartAddress(),
                        Math.round(distance * 10.0) / 10.0,
                        order.getPlannedStartTime(),
                        order.getPlannedEndTime(),
                        PhoneMaskUtils.mask(order.getBlindUser().getPhone()),
                        order.getExpectedDurationMinutes(),
                        order.getPacePreference(),
                        order.getHasGuideDogThisRun(),
                        order.getSpecialNotes(),
                        false
                ));
            }
        }

        // 2. 全城广播求助订单（不限距离，但只展示未过期的）
        List<RunOrder> helpOrders = runOrderRepository.findByStatusIn(
                List.of(OrderStatus.NEEDS_HELP));

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (RunOrder order : helpOrders) {
            if (order.getPlannedStartTime().isBefore(now)) continue;
            double distance = GeoUtils.distanceKm(
                    volunteerLat, volunteerLng,
                    order.getStartLatitude(), order.getStartLongitude()
            );
            result.add(new AvailableOrderResponse(
                    order.getId(),
                    order.getStartAddress(),
                    Math.round(distance * 10.0) / 10.0,
                    order.getPlannedStartTime(),
                    order.getPlannedEndTime(),
                    PhoneMaskUtils.mask(order.getBlindUser().getPhone()),
                    order.getExpectedDurationMinutes(),
                    order.getPacePreference(),
                    order.getHasGuideDogThisRun(),
                    order.getSpecialNotes(),
                    true
            ));
        }

        result.sort((a, b) -> {
            if (a.needsHelp() != b.needsHelp()) return a.needsHelp() ? 1 : -1; // 普通单优先，求助单置后
            return Double.compare(a.distanceKm(), b.distanceKm());
        });
        return result.stream().limit(20).toList();
    }
}

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
        List<RunOrder> pendingOrders = runOrderRepository.findByStatusIn(
                List.of(OrderStatus.PENDING_MATCH, OrderStatus.PENDING_ACCEPT, OrderStatus.REMATCHING));

        List<AvailableOrderResponse> result = new ArrayList<>();
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
                        order.getSpecialNotes()
                ));
            }
        }

        result.sort((a, b) -> Double.compare(a.distanceKm(), b.distanceKm()));
        return result.stream().limit(20).toList();
    }
}

package com.example.demo.controller;

import com.example.demo.dto.ApiResponse;
import com.example.demo.dto.BlindLocationRequest;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.service.BlindLocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 盲人位置控制器 —— 盲人位置上报 + 查询志愿者实时位置（REST 降级）
 *
 * 【REST 降级说明】
 * 盲人优先通过 WebSocket 接收志愿者实时位置（VOLUNTEER_LOCATION_UPDATE）。
 * 当 WebSocket 断开或不可用时，前端可调用 GET /api/blind/volunteer-location 作为降级方案。
 */
@Slf4j
@RestController
@RequestMapping("/api/blind")
public class BlindLocationController {

    private static final String VOLUNTEER_LOCATION_PREFIX = "vol:loc:";
    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED
    );

    private final BlindLocationService blindLocationService;
    private final RunOrderRepository runOrderRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public BlindLocationController(BlindLocationService blindLocationService,
                                    RunOrderRepository runOrderRepository,
                                    StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper) {
        this.blindLocationService = blindLocationService;
        this.runOrderRepository = runOrderRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 盲人上报实时位置 */
    @PostMapping("/location")
    public ResponseEntity<?> updateLocation(@Valid @RequestBody BlindLocationRequest request) {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        blindLocationService.updateLocation(userId, request);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * REST 降级：查询当前订单中志愿者的实时位置
     *
     * 从 Redis vol:loc:{volunteerId} 读取志愿者最新位置。
     * 仅在订单状态为 DRIVER_EN_ROUTE 或 DRIVER_ARRIVED 时返回。
     */
    @GetMapping("/volunteer-location")
    public ResponseEntity<ApiResponse<?>> getVolunteerLocation() {
        Long blindUserId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        List<RunOrder> activeOrders = runOrderRepository
                .findByBlindUserIdAndStatusInFetchVolunteer(blindUserId, ACTIVE_STATUSES);

        if (activeOrders.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "没有进行中的订单"));
        }

        RunOrder order = activeOrders.get(0);
        if (order.getVolunteer() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "订单暂无志愿者"));
        }

        Long volunteerId = order.getVolunteer().getId();
        String redisKey = VOLUNTEER_LOCATION_PREFIX + volunteerId;
        String locationJson = redisTemplate.opsForValue().get(redisKey);

        if (locationJson == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "志愿者位置暂不可用"));
        }

        try {
            JsonNode node = objectMapper.readTree(locationJson);
            Map<String, Object> data = Map.of(
                    "lat", node.get("lat").asDouble(),
                    "lng", node.get("lng").asDouble(),
                    "orderId", order.getId(),
                    "orderStatus", order.getStatus().name()
            );
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (Exception e) {
            log.error("解析志愿者位置数据失败: volunteerId={}, error={}", volunteerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "位置数据解析失败"));
        }
    }
}

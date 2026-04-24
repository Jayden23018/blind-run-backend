package com.example.demo.controller;

import com.example.demo.dto.CreateReviewRequest;
import com.example.demo.dto.ReviewResponse;
import com.example.demo.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.example.demo.util.SecurityUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 评价控制器
 *
 * POST /api/orders/{id}/review  → 盲人对志愿者评价
 * GET  /api/orders/{id}/reviews → 查询订单评价
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<?> createReview(@PathVariable @Min(1) Long id, @Valid @RequestBody CreateReviewRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        reviewService.createReview(id, userId, request.getRating(), request.getComment());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true));
    }

    @GetMapping("/{id}/reviews")
    public ResponseEntity<?> getReview(@PathVariable @Min(1) Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        ReviewResponse review = reviewService.getReview(id, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("data", review);
        return ResponseEntity.ok(result);
    }
}

package com.example.demo.repository;

import com.example.demo.entity.OrderReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderReviewRepository extends JpaRepository<OrderReview, Long> {
    boolean existsByOrderId(Long orderId);
    Optional<OrderReview> findByOrderId(Long orderId);

    /** 批量查多订单的评价（N+1 防护：志愿者首页聚合接口近期记录评分用） */
    List<OrderReview> findByOrderIdIn(List<Long> orderIds);
}

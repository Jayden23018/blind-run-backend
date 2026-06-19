package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.event.OrderCreatedEvent;
import com.example.demo.repository.RunOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 匹配服务 —— 监听订单创建事件，委托给 DispatchService 执行串行派单
 *
 * 原方案：同时推送给 top 3 志愿者（广播抢单）
 * 新方案：逐一推送，30s 超时后推下一个（串行派单）
 */
@Slf4j
@Component
public class MatchingService {

    private final RunOrderRepository runOrderRepository;
    private final DispatchService dispatchService;

    public MatchingService(RunOrderRepository runOrderRepository,
                           DispatchService dispatchService) {
        this.runOrderRepository = runOrderRepository;
        this.dispatchService = dispatchService;
    }

    /**
     * 监听订单创建事件，启动串行派单流程
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleOrderCreated(OrderCreatedEvent event) {
        RunOrder order = runOrderRepository.findByIdWithBlindUser(event.getOrder().getId()).orElse(null);
        if (order == null) {
            log.warn("订单 {} 未找到，跳过匹配", event.getOrder().getId());
            return;
        }

        // REMATCHING 状态的订单统一回到 PENDING_MATCH 进入派单流程
        if (order.getStatus() == OrderStatus.REMATCHING) {
            order.setStatus(OrderStatus.PENDING_MATCH);
        }

        if (order.getStatus() == OrderStatus.PENDING_MATCH) {
            log.info("订单 {} 开始串行派单，起跑点: ({}, {})",
                    order.getId(), order.getStartLatitude(), order.getStartLongitude());
            dispatchService.initiateDispatch(order);
        }
    }
}

package com.example.demo.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 派单接受事件 —— 志愿者通过串行派单接单后发布此事件
 *
 * DispatchService 完成协议校验（验证当前派送对象、更新统计、清理 Redis 队列）后，
 * 发布此事件解耦订单状态机跳转。
 * OrderService @EventListener @Async 接收后将订单从 PENDING_ACCEPT 推进到 IN_PROGRESS。
 */
@Getter
public class DispatchAcceptedEvent extends ApplicationEvent {

    private final Long orderId;
    private final Long volunteerId;

    public DispatchAcceptedEvent(Object source, Long orderId, Long volunteerId) {
        super(source);
        this.orderId = orderId;
        this.volunteerId = volunteerId;
    }
}

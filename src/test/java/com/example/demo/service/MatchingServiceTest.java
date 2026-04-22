package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.User;
import com.example.demo.event.OrderCreatedEvent;
import com.example.demo.repository.RunOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MatchingService 单元测试 —— 验证事件监听后委托给 DispatchService
 */
@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock
    private RunOrderRepository runOrderRepository;

    @Mock
    private DispatchService dispatchService;

    private MatchingService matchingService;

    @BeforeEach
    void setUp() {
        matchingService = new MatchingService(runOrderRepository, dispatchService);
    }

    /** PENDING_MATCH 订单触发 initiateDispatch */
    @Test
    void testPendingMatchTriggersDispatch() {
        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.PENDING_MATCH);
        order.setStartLatitude(39.9);
        order.setStartLongitude(116.4);
        order.setStartAddress("朝阳公园南门");
        order.setPlannedStartTime(LocalDateTime.now().plusHours(1));
        order.setPlannedEndTime(LocalDateTime.now().plusHours(2));

        User blindUser = new User();
        order.setBlindUser(blindUser);

        when(runOrderRepository.findByIdWithBlindUser(1001L)).thenReturn(Optional.of(order));

        matchingService.handleOrderCreated(new OrderCreatedEvent(this, order));

        verify(dispatchService).initiateDispatch(order);
    }

    /** REMATCHING 订单回到 PENDING_MATCH 再触发派单 */
    @Test
    void testRematchingConvertsToPendingMatch() {
        RunOrder order = new RunOrder();
        order.setId(1002L);
        order.setStatus(OrderStatus.REMATCHING);
        order.setStartLatitude(39.9);
        order.setStartLongitude(116.4);
        order.setStartAddress("朝阳公园南门");
        order.setPlannedStartTime(LocalDateTime.now().plusHours(1));
        order.setPlannedEndTime(LocalDateTime.now().plusHours(2));

        User blindUser = new User();
        order.setBlindUser(blindUser);

        when(runOrderRepository.findByIdWithBlindUser(1002L)).thenReturn(Optional.of(order));

        matchingService.handleOrderCreated(new OrderCreatedEvent(this, order));

        assertEquals(OrderStatus.PENDING_MATCH, order.getStatus());
        verify(dispatchService).initiateDispatch(order);
    }

    /** 订单未找到时不调用 dispatch */
    @Test
    void testOrderNotFound() {
        when(runOrderRepository.findByIdWithBlindUser(9999L)).thenReturn(Optional.empty());

        RunOrder phantomOrder = new RunOrder();
        phantomOrder.setId(9999L);
        matchingService.handleOrderCreated(new OrderCreatedEvent(this, phantomOrder));

        verify(dispatchService, never()).initiateDispatch(any());
    }
}

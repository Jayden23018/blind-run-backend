package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.exception.OrderPermissionException;

import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 订单取消测试 —— 验证取消逻辑的身份判断和状态规则
 */
@ExtendWith(MockitoExtension.class)
class OrderCancelTest {

    @Mock
    private RunOrderRepository runOrderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BlindProfileRepository blindProfileRepository;

    @Mock
    private VolunteerProfileRepository volunteerProfileRepository;

    @Mock
    private OrderStatusLogService statusLogService;

    @Mock
    private EmergencyContactService emergencyContactService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProximityService proximityService;

    @Mock
    private DispatchService dispatchService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(runOrderRepository, userRepository, eventPublisher,
                blindProfileRepository, volunteerProfileRepository, statusLogService,
                emergencyContactService, notificationService, proximityService, dispatchService);
    }

    /** 盲人在 IN_PROGRESS 状态取消 → 403 */
    @Test
    void testBlindCancelInProgress() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);
        order.setStatus(OrderStatus.IN_PROGRESS);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));

        OrderPermissionException ex = assertThrows(OrderPermissionException.class,
                () -> orderService.cancelOrder(1001L, 1L));
        assertTrue(ex.getMessage().contains("服务进行中"));
    }

    /** 志愿者取消他人订单 → 403 */
    @Test
    void testStrangerCancel() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setStatus(OrderStatus.PENDING_MATCH);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));

        assertThrows(OrderPermissionException.class,
                () -> orderService.cancelOrder(1001L, 99L));
    }

    /** 盲人在 PENDING_MATCH 取消 → CANCELLED */
    @Test
    void testBlindCancelPendingMatch() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setStatus(OrderStatus.PENDING_MATCH);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(CancelledBy.BLIND, order.getCancelledBy());
    }

    /** 盲人在 PENDING_ACCEPT 取消 → CANCELLED */
    @Test
    void testBlindCancelPendingAccept() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setStatus(OrderStatus.PENDING_ACCEPT);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(CancelledBy.BLIND, order.getCancelledBy());
    }

    /** 志愿者在 IN_PROGRESS 取消 → CANCELLED（爽约） */
    @Test
    void testVolunteerCancelInProgress() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);
        order.setStatus(OrderStatus.IN_PROGRESS);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 2L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(CancelledBy.VOLUNTEER, order.getCancelledBy());
    }

    /** 志愿者在 PENDING_ACCEPT 取消 → REMATCHING */
    @Test
    void testVolunteerCancelPendingAccept() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);
        order.setStatus(OrderStatus.PENDING_ACCEPT);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 2L);

        assertEquals(OrderStatus.REMATCHING, order.getStatus());
        assertEquals(CancelledBy.VOLUNTEER, order.getCancelledBy());
        assertNull(order.getVolunteer());
        assertEquals(1, order.getRematchCount());
        assertNotNull(order.getLastRematchAt());
        assertNotNull(order.getRematchNotifyAt());

        // 验证发布了匹配事件
        verify(eventPublisher).publishEvent(any());
        // 验证通知了盲人
        verify(notificationService).sendNotification(eq(1L), eq("REMATCHING"), eq(TargetRole.BLIND_USER), isNull());
    }

    /** 志愿者在 DRIVER_EN_ROUTE 取消 → REMATCHING */
    @Test
    void testVolunteerCancelDriverEnRoute() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);
        order.setStatus(OrderStatus.DRIVER_EN_ROUTE);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 2L);

        assertEquals(OrderStatus.REMATCHING, order.getStatus());
        assertNull(order.getVolunteer());
        assertEquals(1, order.getRematchCount());
    }

    /** 志愿者在 DRIVER_ARRIVED 取消 → REMATCHING */
    @Test
    void testVolunteerCancelDriverArrived() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);
        order.setStatus(OrderStatus.DRIVER_ARRIVED);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 2L);

        assertEquals(OrderStatus.REMATCHING, order.getStatus());
        assertNull(order.getVolunteer());
    }

    /** 盲人在 REMATCHING 状态取消 → CANCELLED */
    @Test
    void testBlindCancelRematching() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setBlindUser(blindUser);
        order.setStatus(OrderStatus.REMATCHING);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderService.cancelOrder(1001L, 1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(CancelledBy.BLIND, order.getCancelledBy());
        // 验证清除了 rematchNotifyAt
        assertNull(order.getRematchNotifyAt());
    }
}

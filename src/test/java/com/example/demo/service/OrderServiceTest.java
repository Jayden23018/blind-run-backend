package com.example.demo.service;

import com.example.demo.dto.CreateOrderRequest;
import com.example.demo.dto.OrderResponse;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.TargetRole;
import com.example.demo.entity.User;
import com.example.demo.exception.DuplicateOrderException;
import com.example.demo.exception.OrderNotFoundException;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.exception.OrderStatusException;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单创建与生命周期单元测试
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private RunOrderRepository runOrderRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BlindProfileRepository blindProfileRepository;
    @Mock private VolunteerProfileRepository volunteerProfileRepository;
    @Mock private OrderStatusLogService statusLogService;
    @Mock private EmergencyContactService emergencyContactService;
    @Mock private NotificationService notificationService;
    @Mock private ProximityService proximityService;
    @Mock private VolunteerLocationService volunteerLocationService;

    private OrderCreationService orderCreationService;
    private OrderLifecycleService orderLifecycleService;

    @BeforeEach
    void setUp() {
        orderCreationService = new OrderCreationService(
                runOrderRepository, userRepository, eventPublisher,
                blindProfileRepository, emergencyContactService, statusLogService);

        orderLifecycleService = new OrderLifecycleService(
                runOrderRepository, userRepository, eventPublisher,
                volunteerProfileRepository, statusLogService,
                notificationService, proximityService, volunteerLocationService);
    }

    /** 正常创建订单 */
    @Test
    void testCreateOrderSuccess() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setStartLatitude(39.9042);
        request.setStartLongitude(116.4074);
        request.setStartAddress("朝阳公园南门");
        request.setPlannedStartTime(LocalDateTime.now().plusHours(1));
        request.setPlannedEndTime(LocalDateTime.now().plusHours(2));

        when(runOrderRepository.existsByBlindUserIdAndStatusIn(anyLong(), anyList())).thenReturn(false);
        when(emergencyContactService.hasContacts(anyLong())).thenReturn(true);
        when(runOrderRepository.save(any(RunOrder.class))).thenAnswer(invocation -> {
            RunOrder order = invocation.getArgument(0);
            order.setId(1001L);
            return order;
        });
        when(userRepository.getReferenceById(1L)).thenReturn(new User());

        OrderResponse response = orderCreationService.createOrder(1L, request);

        assertEquals(1001L, response.getId());
        assertEquals(OrderStatus.PENDING_MATCH, response.getStatus());
        verify(eventPublisher).publishEvent(any());
    }

    /** 重复订单 */
    @Test
    void testCreateDuplicateOrder() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setStartLatitude(39.9042);
        request.setStartLongitude(116.4074);
        request.setStartAddress("朝阳公园南门");
        request.setPlannedStartTime(LocalDateTime.now().plusHours(1));
        request.setPlannedEndTime(LocalDateTime.now().plusHours(2));

        when(runOrderRepository.existsByBlindUserIdAndStatusIn(anyLong(), anyList())).thenReturn(true);

        assertThrows(DuplicateOrderException.class, () -> orderCreationService.createOrder(1L, request));
    }

    /** 结束时间早于开始时间 */
    @Test
    void testCreateOrderInvalidTime() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setStartLatitude(39.9042);
        request.setStartLongitude(116.4074);
        request.setStartAddress("朝阳公园南门");
        request.setPlannedStartTime(LocalDateTime.now().plusHours(2));
        request.setPlannedEndTime(LocalDateTime.now().plusHours(1));

        assertThrows(IllegalArgumentException.class, () -> orderCreationService.createOrder(1L, request));
    }

    /** 开始时间早于当前时间 */
    @Test
    void testCreateOrderPastStartTime() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setStartLatitude(39.9042);
        request.setStartLongitude(116.4074);
        request.setStartAddress("朝阳公园南门");
        request.setPlannedStartTime(LocalDateTime.now().minusHours(1));
        request.setPlannedEndTime(LocalDateTime.now().plusHours(1));

        assertThrows(IllegalArgumentException.class, () -> orderCreationService.createOrder(1L, request));
    }

    /** 接单成功 → IN_PROGRESS（直接接单从 PENDING_MATCH 状态） */
    @Test
    void testAcceptOrderSuccess() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.PENDING_MATCH);
        order.setBlindUser(blindUser);

        com.example.demo.entity.VolunteerProfile profile = new com.example.demo.entity.VolunteerProfile();
        profile.setVerified(true);
        profile.setRegistrationStep(com.example.demo.entity.RegistrationStep.STEP_4_COMPLETED);
        when(volunteerProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);
        when(userRepository.getReferenceById(2L)).thenReturn(new User());

        orderLifecycleService.acceptOrder(1001L, 2L);

        assertEquals(OrderStatus.IN_PROGRESS, order.getStatus());
        assertNotNull(order.getAcceptedAt());
    }

    /** 接单失败：直接接单只允许 PENDING_MATCH/REMATCHING，PENDING_ACCEPT 由派单事件处理 */
    @Test
    void testAcceptOrderWrongStatus() {
        com.example.demo.entity.VolunteerProfile profile = new com.example.demo.entity.VolunteerProfile();
        profile.setVerified(true);
        profile.setRegistrationStep(com.example.demo.entity.RegistrationStep.STEP_4_COMPLETED);
        when(volunteerProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.COMPLETED);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));

        assertThrows(OrderStatusException.class, () -> orderLifecycleService.acceptOrder(1001L, 2L));
    }

    /** 接单失败：订单不存在 */
    @Test
    void testAcceptOrderNotFound() {
        com.example.demo.entity.VolunteerProfile profile = new com.example.demo.entity.VolunteerProfile();
        profile.setVerified(true);
        profile.setRegistrationStep(com.example.demo.entity.RegistrationStep.STEP_4_COMPLETED);
        when(volunteerProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        when(runOrderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderLifecycleService.acceptOrder(999L, 2L));
    }

    /** 结束服务成功 → COMPLETED */
    @Test
    void testFinishOrderSuccess() {
        User blindUser = new User();
        blindUser.setId(1L);

        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setBlindUser(blindUser);
        order.setVolunteer(volunteer);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);

        orderLifecycleService.finishOrder(1001L, 2L);

        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertNotNull(order.getFinishedAt());
    }

    /** 结束服务失败：不是接单的志愿者 */
    @Test
    void testFinishOrderWrongVolunteer() {
        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setVolunteer(volunteer);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));

        assertThrows(OrderPermissionException.class, () -> orderLifecycleService.finishOrder(1001L, 3L));
    }

    /** 结束服务失败：状态不是 IN_PROGRESS */
    @Test
    void testFinishOrderWrongStatus() {
        User volunteer = new User();
        volunteer.setId(2L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.PENDING_ACCEPT);
        order.setVolunteer(volunteer);

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));

        assertThrows(OrderStatusException.class, () -> orderLifecycleService.finishOrder(1001L, 2L));
    }

    /** 从 REMATCHING 状态接单成功 → IN_PROGRESS，清除 rematchNotifyAt */
    @Test
    void testAcceptOrderFromRematching() {
        User blindUser = new User();
        blindUser.setId(1L);

        RunOrder order = new RunOrder();
        order.setId(1001L);
        order.setStatus(OrderStatus.REMATCHING);
        order.setBlindUser(blindUser);

        com.example.demo.entity.VolunteerProfile profile = new com.example.demo.entity.VolunteerProfile();
        profile.setVerified(true);
        profile.setRegistrationStep(com.example.demo.entity.RegistrationStep.STEP_4_COMPLETED);
        when(volunteerProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        when(runOrderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(runOrderRepository.save(any(RunOrder.class))).thenReturn(order);
        when(userRepository.getReferenceById(2L)).thenReturn(new User());

        orderLifecycleService.acceptOrder(1001L, 2L);

        assertEquals(OrderStatus.IN_PROGRESS, order.getStatus());
        assertNotNull(order.getAcceptedAt());
        assertNull(order.getRematchNotifyAt());
        verify(notificationService).sendNotification(eq(1L), eq("REMATCH_ACCEPTED"), eq(TargetRole.BLIND_USER), any());
    }
}

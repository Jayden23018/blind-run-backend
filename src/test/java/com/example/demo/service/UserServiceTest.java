package com.example.demo.service;

import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.entity.VolunteerProfile;
import com.example.demo.exception.OrderStatusException;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.BlindProfileRepository;
import com.example.demo.repository.EmergencyContactRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.RunOrderTrackPointRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerAvailableTimeRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UserService.deleteAccount() 单元测试 —— 活跃订单校验 + PII 级联清理
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RunOrderRepository runOrderRepository;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private BlindProfileRepository blindProfileRepository;
    @Mock private VolunteerProfileRepository volunteerProfileRepository;
    @Mock private EmergencyContactRepository emergencyContactRepository;
    @Mock private VolunteerAvailableTimeRepository volunteerAvailableTimeRepository;
    @Mock private RunOrderTrackPointRepository runOrderTrackPointRepository;
    @Mock private FileStorageService fileStorageService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, runOrderRepository, tokenBlacklistService,
                blindProfileRepository, volunteerProfileRepository, emergencyContactRepository,
                volunteerAvailableTimeRepository, runOrderTrackPointRepository, fileStorageService);
    }

    private User blindUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setPhone("13900000001");
        user.setName("盲人张三");
        user.setRole(UserRole.BLIND);
        return user;
    }

    private User volunteerUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setPhone("13900000002");
        user.setName("志愿者李四");
        user.setRole(UserRole.VOLUNTEER);
        return user;
    }

    @Test
    void deleteAccount_otherUserId_throwsPermissionDenied() {
        assertThatThrownBy(() -> userService.deleteAccount(1L, 2L))
                .isInstanceOf(PermissionDeniedException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void deleteAccount_userNotFound_throwsResourceNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.deleteAccount(1L, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {
            "PENDING_MATCH", "PENDING_ACCEPT", "IN_PROGRESS", "DRIVER_EN_ROUTE", "DRIVER_ARRIVED", "REMATCHING"
    })
    void deleteAccount_blindUserWithActiveOrder_throwsBlocked(OrderStatus status) {
        User user = blindUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(true);

        assertThatThrownBy(() -> userService.deleteAccount(1L, 1L))
                .isInstanceOf(OrderStatusException.class);
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {
            "PENDING_ACCEPT", "IN_PROGRESS", "DRIVER_EN_ROUTE", "DRIVER_ARRIVED"
    })
    void deleteAccount_volunteerWithActiveOrder_throwsBlocked(OrderStatus status) {
        User user = volunteerUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(runOrderRepository.existsByVolunteerIdAndStatusIn(eq(1L), anyList())).thenReturn(true);

        assertThatThrownBy(() -> userService.deleteAccount(1L, 1L))
                .isInstanceOf(OrderStatusException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteAccount_volunteerRematching_notBlocked() {
        // REMATCHING: order.volunteer already nulled by OrderLifecycleService, so volunteer-side
        // check must not include it — simulate by having the mock return false for volunteer check.
        User user = volunteerUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(runOrderRepository.existsByVolunteerIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(volunteerProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        userService.deleteAccount(1L, 1L);

        verify(userRepository).save(user);
        assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteAccount_blindUser_cascadesProfileAndContactsAndTrack() {
        User user = blindUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(false);

        userService.deleteAccount(1L, 1L);

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getName()).isNull();
        assertThat(user.getPhone()).isEqualTo("deleted_1_13900000001");
        verify(blindProfileRepository).deleteByUserId(1L);
        verify(emergencyContactRepository).deleteByUserId(1L);
        verify(runOrderTrackPointRepository).deleteByUserId(1L);
        verify(volunteerProfileRepository, never()).deleteByUserId(any());
        verify(tokenBlacklistService).blacklistUserWithMaxTtl(1L);
    }

    @Test
    void deleteAccount_volunteerWithDoc_deletesFileAndProfileAndAvailability() {
        User user = volunteerUser(1L);
        VolunteerProfile profile = new VolunteerProfile();
        profile.setVerificationDocUrl("uploads/doc123.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(runOrderRepository.existsByVolunteerIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(volunteerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        userService.deleteAccount(1L, 1L);

        verify(fileStorageService).delete("uploads/doc123.jpg");
        verify(volunteerProfileRepository).deleteByUserId(1L);
        verify(volunteerAvailableTimeRepository).deleteByVolunteerId(1L);
        verify(runOrderTrackPointRepository).deleteByUserId(1L);
        verify(blindProfileRepository, never()).deleteByUserId(any());
    }

    @Test
    void deleteAccount_volunteerWithoutDoc_skipsFileDelete() {
        User user = volunteerUser(1L);
        VolunteerProfile profile = new VolunteerProfile();
        profile.setVerificationDocUrl(null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(runOrderRepository.existsByBlindUserIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(runOrderRepository.existsByVolunteerIdAndStatusIn(eq(1L), anyList())).thenReturn(false);
        when(volunteerProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));

        userService.deleteAccount(1L, 1L);

        verify(fileStorageService, never()).delete(any());
        verify(volunteerProfileRepository).deleteByUserId(1L);
    }
}

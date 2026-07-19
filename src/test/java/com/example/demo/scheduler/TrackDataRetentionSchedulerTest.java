package com.example.demo.scheduler;

import com.example.demo.repository.RunOrderTrackPointRepository;
import com.example.demo.service.SchedulerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * TrackDataRetentionScheduler 单元测试 —— 留存期清理任务的加锁/删除/放锁流程
 */
@ExtendWith(MockitoExtension.class)
class TrackDataRetentionSchedulerTest {

    @Mock private RunOrderTrackPointRepository trackPointRepository;
    @Mock private SchedulerLockService schedulerLockService;

    private TrackDataRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TrackDataRetentionScheduler(trackPointRepository, schedulerLockService);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 90);
    }

    @Test
    void cleanup_deletesPointsOlderThanRetentionWindow() {
        when(schedulerLockService.tryLock("trackDataRetention", 300)).thenReturn(true);

        scheduler.cleanupExpiredTrackPoints();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(trackPointRepository).deleteByRecordedAtBefore(cutoffCaptor.capture());
        // cutoff 应约等于 now - 90 天（容忍测试执行的几秒误差）
        LocalDateTime expected = LocalDateTime.now().minusDays(90);
        assertTrue(Math.abs(java.time.Duration.between(expected, cutoffCaptor.getValue()).getSeconds()) < 5);
        verify(schedulerLockService).releaseLock("trackDataRetention");
    }

    @Test
    void cleanup_lockNotAcquired_skipsDelete() {
        when(schedulerLockService.tryLock("trackDataRetention", 300)).thenReturn(false);

        scheduler.cleanupExpiredTrackPoints();

        verify(trackPointRepository, never()).deleteByRecordedAtBefore(any());
        verify(schedulerLockService, never()).releaseLock(anyString());
    }
}

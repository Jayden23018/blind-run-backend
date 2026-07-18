package com.example.demo.service;

import com.example.demo.dto.TrackPointDto;
import com.example.demo.dto.TrackStatsDto;
import com.example.demo.entity.RunOrderTrackPoint;
import com.example.demo.entity.UserRole;
import com.example.demo.repository.RunOrderTrackPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RunOrderTrackService 单元测试 —— 抽稀去重 + 里程/配速统计
 */
@ExtendWith(MockitoExtension.class)
class RunOrderTrackServiceTest {

    @Mock private RunOrderTrackPointRepository trackPointRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RunOrderTrackService trackService;

    @BeforeEach
    void setUp() {
        trackService = new RunOrderTrackService(trackPointRepository, redisTemplate);
        ReflectionTestUtils.setField(trackService, "sampleIntervalSeconds", 10L);
    }

    @Test
    void recordIfDue_acquiresLock_savesPoint() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("track:sample:1:VOLUNTEER"), eq("1"), eq(10L), any())).thenReturn(true);

        trackService.recordIfDue(1L, 99L, UserRole.VOLUNTEER, 31.23, 121.47);

        verify(trackPointRepository).save(argThat(p ->
                p.getOrderId().equals(1L) && p.getUserId().equals(99L)
                        && p.getRole() == UserRole.VOLUNTEER
                        && p.getLatitude() == 31.23 && p.getLongitude() == 121.47));
    }

    @Test
    void recordIfDue_withinSampleWindow_skipsSave() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        trackService.recordIfDue(1L, 99L, UserRole.VOLUNTEER, 31.23, 121.47);

        verify(trackPointRepository, never()).save(any());
    }

    @Test
    void getStats_fewerThanTwoPoints_returnsEmpty() {
        when(trackPointRepository.findByOrderIdAndRoleOrderByRecordedAtAsc(1L, UserRole.BLIND))
                .thenReturn(List.of(point(31.23, 121.47, LocalDateTime.now())));

        TrackStatsDto stats = trackService.getStats(1L, UserRole.BLIND);

        assertSame(TrackStatsDto.EMPTY, stats);
    }

    @Test
    void getStats_computesDistanceDurationAndPace() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 18, 9, 0, 0);
        // 约 0.111km 之间的两点（纬度相差 0.001 度 ≈ 111 米），间隔 60 秒
        List<RunOrderTrackPoint> points = List.of(
                point(31.230, 121.470, t0),
                point(31.231, 121.470, t0.plusSeconds(60))
        );
        when(trackPointRepository.findByOrderIdAndRoleOrderByRecordedAtAsc(1L, UserRole.VOLUNTEER))
                .thenReturn(points);

        TrackStatsDto stats = trackService.getStats(1L, UserRole.VOLUNTEER);

        assertEquals(60L, stats.durationSeconds());
        assertTrue(stats.distanceMeters() > 100 && stats.distanceMeters() < 120,
                "预期约 111 米，实际: " + stats.distanceMeters());
        assertNotNull(stats.avgPaceSecPerKm());
    }

    @Test
    void getTrack_mapsEntitiesToDto() {
        LocalDateTime t0 = LocalDateTime.now();
        when(trackPointRepository.findByOrderIdAndRoleOrderByRecordedAtAsc(1L, UserRole.BLIND))
                .thenReturn(List.of(point(31.23, 121.47, t0)));

        List<TrackPointDto> track = trackService.getTrack(1L, UserRole.BLIND);

        assertEquals(1, track.size());
        assertEquals(31.23, track.get(0).lat());
        assertEquals(121.47, track.get(0).lng());
        assertEquals(t0, track.get(0).recordedAt());
    }

    private RunOrderTrackPoint point(double lat, double lng, LocalDateTime recordedAt) {
        RunOrderTrackPoint p = new RunOrderTrackPoint();
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setRecordedAt(recordedAt);
        return p;
    }
}

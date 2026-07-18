package com.example.demo.service;

import com.example.demo.dto.TrackPointDto;
import com.example.demo.dto.TrackStatsDto;
import com.example.demo.entity.RunOrderTrackPoint;
import com.example.demo.entity.UserRole;
import com.example.demo.repository.RunOrderTrackPointRepository;
import com.example.demo.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 陪跑轨迹服务 —— 抽稀采样落库 + 轨迹/统计查询
 */
@Slf4j
@Service
public class RunOrderTrackService {

    private final RunOrderTrackPointRepository trackPointRepository;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.track.sample-interval-seconds:10}")
    private long sampleIntervalSeconds;

    public RunOrderTrackService(RunOrderTrackPointRepository trackPointRepository,
                                 StringRedisTemplate redisTemplate) {
        this.trackPointRepository = trackPointRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 抽稀落库：同一订单+角色在采样窗口内只落一条点（Redis SETNX 原子占位，跟
     * EmergencyService 的冷却锁是同一种模式）
     */
    public void recordIfDue(Long orderId, Long userId, UserRole role, double lat, double lng) {
        String sampleKey = "track:sample:" + orderId + ":" + role;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(sampleKey, "1", sampleIntervalSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        RunOrderTrackPoint point = new RunOrderTrackPoint();
        point.setOrderId(orderId);
        point.setUserId(userId);
        point.setRole(role);
        point.setLatitude(lat);
        point.setLongitude(lng);
        trackPointRepository.save(point);
    }

    public List<TrackPointDto> getTrack(Long orderId, UserRole role) {
        return trackPointRepository.findByOrderIdAndRoleOrderByRecordedAtAsc(orderId, role).stream()
                .map(p -> new TrackPointDto(p.getLatitude(), p.getLongitude(), p.getRecordedAt()))
                .collect(Collectors.toList());
    }

    public TrackStatsDto getStats(Long orderId, UserRole role) {
        List<RunOrderTrackPoint> points = trackPointRepository
                .findByOrderIdAndRoleOrderByRecordedAtAsc(orderId, role);
        if (points.size() < 2) {
            return TrackStatsDto.EMPTY;
        }

        double distanceMeters = 0;
        for (int i = 1; i < points.size(); i++) {
            RunOrderTrackPoint prev = points.get(i - 1);
            RunOrderTrackPoint curr = points.get(i);
            distanceMeters += GeoUtils.distanceKm(
                    prev.getLatitude(), prev.getLongitude(),
                    curr.getLatitude(), curr.getLongitude()) * 1000;
        }

        LocalDateTime start = points.get(0).getRecordedAt();
        LocalDateTime end = points.get(points.size() - 1).getRecordedAt();
        long durationSeconds = Duration.between(start, end).getSeconds();

        Double avgPaceSecPerKm = distanceMeters > 0
                ? durationSeconds / (distanceMeters / 1000)
                : null;

        return new TrackStatsDto(distanceMeters, durationSeconds, avgPaceSecPerKm);
    }
}

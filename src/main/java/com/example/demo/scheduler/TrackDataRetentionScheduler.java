package com.example.demo.scheduler;

import com.example.demo.repository.RunOrderTrackPointRepository;
import com.example.demo.service.SchedulerLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 陪跑轨迹数据留存清理 —— 每天凌晨 3 点删除超过留存期的轨迹点。
 * 轨迹（经纬度+时间戳）属于 PIPL 列举的敏感个人信息（行踪轨迹），
 * 留存期限见 docs/轨迹数据留存策略.md。
 */
@Slf4j
@Component
public class TrackDataRetentionScheduler {

    private final RunOrderTrackPointRepository trackPointRepository;
    private final SchedulerLockService schedulerLockService;

    @Value("${app.track.retention-days:90}")
    private int retentionDays;

    public TrackDataRetentionScheduler(RunOrderTrackPointRepository trackPointRepository,
                                        SchedulerLockService schedulerLockService) {
        this.trackPointRepository = trackPointRepository;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTrackPoints() {
        if (!schedulerLockService.tryLock("trackDataRetention", 300)) return;
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            trackPointRepository.deleteByRecordedAtBefore(cutoff);
            log.info("轨迹数据留存清理完成，删除 {} 之前的记录", cutoff);
        } finally {
            schedulerLockService.releaseLock("trackDataRetention");
        }
    }
}

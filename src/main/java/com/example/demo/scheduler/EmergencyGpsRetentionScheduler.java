package com.example.demo.scheduler;

import com.example.demo.repository.EmergencyEventRepository;
import com.example.demo.service.SchedulerLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 紧急事件 GPS 坐标留存清理 —— 每天凌晨 3 点清空超过留存期事件的原始坐标（行本身保留，用于纠纷复核审计）。
 * GPS 坐标属于 PIPL 列举的敏感个人信息（行踪轨迹），留存期限与 app.track.retention-days 一致，
 * 见 docs/轨迹数据留存策略.md。
 */
@Slf4j
@Component
public class EmergencyGpsRetentionScheduler {

    private final EmergencyEventRepository eventRepository;
    private final SchedulerLockService schedulerLockService;

    @Value("${app.emergency.gps-retention-days:90}")
    private int retentionDays;

    public EmergencyGpsRetentionScheduler(EmergencyEventRepository eventRepository,
                                           SchedulerLockService schedulerLockService) {
        this.eventRepository = eventRepository;
        this.schedulerLockService = schedulerLockService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredGps() {
        if (!schedulerLockService.tryLock("emergencyGpsRetention", 300)) return;
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int cleared = eventRepository.clearGpsBefore(cutoff);
            log.info("紧急事件GPS留存清理完成，清空 {} 之前的坐标，共 {} 条", cutoff, cleared);
        } finally {
            schedulerLockService.releaseLock("emergencyGpsRetention");
        }
    }
}

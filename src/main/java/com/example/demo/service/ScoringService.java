package com.example.demo.service;

import com.example.demo.dto.ScoredCandidate;
import com.example.demo.entity.*;
import com.example.demo.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

/**
 * 派单评分引擎 —— 纯函数式、无状态，不操作 Redis/DB
 *
 * 评分公式（满分 100）：
 * - 距离得分 × 40
 * - 时间匹配得分 × 25
 * - 历史评分得分 × 20
 * - 接单率得分 × 10
 * - 配速匹配得分 × 5
 */
@Slf4j
@Service
public class ScoringService {

    // 权重常量
    private static final double WEIGHT_DISTANCE = 40.0;
    private static final double WEIGHT_TIME = 25.0;
    private static final double WEIGHT_RATING = 20.0;
    private static final double WEIGHT_ACCEPTANCE = 10.0;
    private static final double WEIGHT_PACE = 5.0;

    // 无数据时的默认分值（归一化 0-1）
    private static final double DEFAULT_TIME_SCORE = 0.5;
    private static final double DEFAULT_RATING_SCORE = 0.7;
    private static final double DEFAULT_ACCEPTANCE_SCORE = 0.7;
    private static final double DEFAULT_PACE_SCORE = 0.5;

    /**
     * 硬性过滤 + 评分，返回按 score DESC 排序的候选列表
     *
     * @param order         订单
     * @param locations     在线志愿者位置列表（来自 Redis），每项含 userId/lat/lng
     * @param profiles      志愿者档案 Map（key=userId）
     * @param availability  志愿者可用时间 Map（key=volunteerId）
     * @param distanceKm    距离阈值
     * @param minOverlap    最低时间重叠率
     * @return 排序后的候选列表
     */
    public List<ScoredCandidate> scoreCandidates(
            RunOrder order,
            List<Map<String, Object>> locations,
            Map<Long, VolunteerProfile> profiles,
            Map<Long, List<VolunteerAvailableTime>> availability,
            double distanceKm,
            double minOverlap) {

        List<ScoredCandidate> candidates = new ArrayList<>();

        for (Map<String, Object> loc : locations) {
            Long volunteerId = toLong(loc.get("userId"));
            if (volunteerId == null) continue;

            double lat = toDouble(loc.get("lat"));
            double lng = toDouble(loc.get("lng"));

            double dist = GeoUtils.distanceKm(
                    order.getStartLatitude(), order.getStartLongitude(), lat, lng);

            // ===== 硬性过滤 =====

            // 距离超限
            if (dist > distanceKm) {
                log.debug("志愿者 {} 距离 {:.1f}km 超过阈值 {}km，过滤", volunteerId, dist, distanceKm);
                continue;
            }

            VolunteerProfile profile = profiles.get(volunteerId);
            if (profile == null) {
                log.debug("志愿者 {} 无档案数据，跳过", volunteerId);
                continue;
            }

            // 注册未完成
            if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
                continue;
            }

            // 身份未认证
            if (!Boolean.TRUE.equals(profile.getVerified())) {
                continue;
            }

            // 导盲犬不兼容
            if (Boolean.TRUE.equals(order.getHasGuideDogThisRun())
                    && !Boolean.TRUE.equals(profile.getAcceptsGuideDog())) {
                continue;
            }

            // 时间重叠率过滤（无可用时间数据时跳过此过滤，不惩罚未设置的志愿者）
            List<VolunteerAvailableTime> volSlots = availability.getOrDefault(volunteerId, List.of());
            double overlap = volSlots.isEmpty() ? DEFAULT_TIME_SCORE : calcTimeOverlap(order, volSlots);
            if (!volSlots.isEmpty() && overlap < minOverlap) {
                log.debug("志愿者 {} 时间重叠率 {} 低于阈值 {}，过滤", volunteerId, overlap, minOverlap);
                continue;
            }

            // ===== 软性评分 =====
            double distanceScore = calcDistanceScore(dist, distanceKm);
            double timeScore = overlap;
            double ratingScore = calcRatingScore(profile);
            double acceptanceScore = calcAcceptanceScore(profile);
            double paceScore = calcPaceScore(order.getPacePreference(), profile.getPaceRange());

            double total = distanceScore * WEIGHT_DISTANCE
                    + timeScore * WEIGHT_TIME
                    + ratingScore * WEIGHT_RATING
                    + acceptanceScore * WEIGHT_ACCEPTANCE
                    + paceScore * WEIGHT_PACE;

            candidates.add(new ScoredCandidate(
                    volunteerId, total,
                    distanceScore * WEIGHT_DISTANCE,
                    timeScore * WEIGHT_TIME,
                    ratingScore * WEIGHT_RATING,
                    acceptanceScore * WEIGHT_ACCEPTANCE,
                    paceScore * WEIGHT_PACE,
                    dist
            ));
        }

        candidates.sort(Comparator.naturalOrder()); // Comparable 实现：按 totalScore DESC
        log.info("评分完成：{} 名候选者通过过滤，评分范围 {:.1f}~{:.1f}",
                candidates.size(),
                candidates.isEmpty() ? 0 : candidates.get(candidates.size() - 1).totalScore(),
                candidates.isEmpty() ? 0 : candidates.get(0).totalScore());

        return candidates;
    }

    /**
     * 距离得分（归一化 0-1）：距离越近得分越高，线性递减
     */
    double calcDistanceScore(double distanceKm, double thresholdKm) {
        return Math.max(0, 1 - distanceKm / thresholdKm);
    }

    /**
     * 时间重叠率（0-1）：计算志愿者可用时间窗口与订单时间的重叠比例
     *
     * 无可用时间数据时返回默认值 0.5（中性，不惩罚未设置的志愿者）
     */
    double calcTimeOverlap(RunOrder order, List<VolunteerAvailableTime> slots) {
        if (slots == null || slots.isEmpty()) {
            return DEFAULT_TIME_SCORE;
        }

        DayOfWeek orderDay = order.getPlannedStartTime().getDayOfWeek();
        LocalTime orderStart = order.getPlannedStartTime().toLocalTime();
        LocalTime orderEnd = order.getPlannedEndTime().toLocalTime();
        long orderDurationMinutes = java.time.Duration.between(
                order.getPlannedStartTime(), order.getPlannedEndTime()).toMinutes();
        if (orderDurationMinutes <= 0) return 0;

        long totalOverlapMinutes = 0;
        for (VolunteerAvailableTime slot : slots) {
            if (!sameDayOfWeek(slot.getDayOfWeek(), orderDay)) continue;

            long overlap = overlapMinutes(
                    slot.getStartTime(), slot.getEndTime(),
                    orderStart, orderEnd);
            totalOverlapMinutes += overlap;
        }

        // 重叠时间不能超过订单时长
        totalOverlapMinutes = Math.min(totalOverlapMinutes, orderDurationMinutes);
        return (double) totalOverlapMinutes / orderDurationMinutes;
    }

    /**
     * 历史评分得分（归一化 0-1）：1-5 星映射到 0-1
     * 无评价的新志愿者给 0.7（鼓励新人）
     */
    double calcRatingScore(VolunteerProfile profile) {
        if (profile.getAvgRating() == null || profile.getTotalRatings() == null || profile.getTotalRatings() == 0) {
            return DEFAULT_RATING_SCORE;
        }
        return (profile.getAvgRating() - 1.0) / 4.0;
    }

    /**
     * 接单率得分（归一化 0-1）
     * 无派单记录时给 0.7
     */
    double calcAcceptanceScore(VolunteerProfile profile) {
        if (profile.getAcceptanceRate() != null && profile.getTotalDispatched() != null && profile.getTotalDispatched() > 0) {
            return profile.getAcceptanceRate();
        }
        return DEFAULT_ACCEPTANCE_SCORE;
    }

    /**
     * 配速匹配得分（归一化 0-1）
     *
     * 逻辑：
     * - 任一方 NO_PREFERENCE → 0.5（中性）
     * - 完全匹配 → 1.0
     * - 相差1级 → 0.6
     * - 相差2级 → 0.2
     * - 相差3级以上 → 0.0
     */
    double calcPaceScore(PacePreference orderPace, PacePreference volunteerPace) {
        if (orderPace == null || orderPace == PacePreference.NO_PREFERENCE
                || volunteerPace == null || volunteerPace == PacePreference.NO_PREFERENCE) {
            return DEFAULT_PACE_SCORE;
        }
        if (orderPace == volunteerPace) return 1.0;

        int diff = Math.abs(orderPace.ordinal() - volunteerPace.ordinal());
        return switch (diff) {
            case 1 -> 0.6;
            case 2 -> 0.2;
            default -> 0.0;
        };
    }

    // ===== 工具方法 =====

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private boolean sameDayOfWeek(String dayStr, DayOfWeek day) {
        try {
            // 兼容 "MONDAY" 和 "MON" 两种格式
            if (dayStr.length() == 3) {
                return day.name().substring(0, 3).equalsIgnoreCase(dayStr);
            }
            return DayOfWeek.valueOf(dayStr.toUpperCase()) == day;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private long overlapMinutes(LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
        long overlapStart = Math.max(toMinutes(start1), toMinutes(start2));
        long overlapEnd = Math.min(toMinutes(end1), toMinutes(end2));
        return Math.max(0, overlapEnd - overlapStart);
    }

    private long toMinutes(LocalTime time) {
        return time.getHour() * 60L + time.getMinute();
    }
}

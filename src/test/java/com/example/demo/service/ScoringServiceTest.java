package com.example.demo.service;

import com.example.demo.dto.ScoredCandidate;
import com.example.demo.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceTest {

    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService();
    }

    // ===== 距离得分 =====

    @Nested
    @DisplayName("距离得分计算")
    class DistanceScoreTest {

        @Test
        @DisplayName("0km 得满分 1.0")
        void zeroDistance_fullScore() {
            assertThat(scoringService.calcDistanceScore(0, 5)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("阈值边界得 0 分")
        void atThreshold_zeroScore() {
            assertThat(scoringService.calcDistanceScore(5, 5)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("超过阈值得 0 分（clamp）")
        void overThreshold_zeroScore() {
            assertThat(scoringService.calcDistanceScore(10, 5)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("2.5km 得 0.5 分（线性递减）")
        void halfDistance_halfScore() {
            assertThat(scoringService.calcDistanceScore(2.5, 5)).isEqualTo(0.5);
        }
    }

    // ===== 历史评分得分 =====

    @Nested
    @DisplayName("历史评分得分计算")
    class RatingScoreTest {

        @Test
        @DisplayName("无评价时默认 0.7（鼓励新人）")
        void noRating_defaultScore() {
            VolunteerProfile profile = new VolunteerProfile();
            assertThat(scoringService.calcRatingScore(profile)).isEqualTo(0.7);
        }

        @Test
        @DisplayName("5 星满分 → 1.0")
        void fiveStars_maxScore() {
            VolunteerProfile profile = new VolunteerProfile();
            profile.setAvgRating(5.0);
            profile.setTotalRatings(10);
            assertThat(scoringService.calcRatingScore(profile)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("1 星最低 → 0.0")
        void oneStar_zeroScore() {
            VolunteerProfile profile = new VolunteerProfile();
            profile.setAvgRating(1.0);
            profile.setTotalRatings(5);
            assertThat(scoringService.calcRatingScore(profile)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("3 星 → 0.5")
        void threeStars_halfScore() {
            VolunteerProfile profile = new VolunteerProfile();
            profile.setAvgRating(3.0);
            profile.setTotalRatings(8);
            assertThat(scoringService.calcRatingScore(profile)).isEqualTo(0.5);
        }
    }

    // ===== 接单率得分 =====

    @Nested
    @DisplayName("接单率得分计算")
    class AcceptanceScoreTest {

        @Test
        @DisplayName("无派单记录默认 0.7")
        void noHistory_defaultScore() {
            VolunteerProfile profile = new VolunteerProfile();
            assertThat(scoringService.calcAcceptanceScore(profile)).isEqualTo(0.7);
        }

        @Test
        @DisplayName("接单率 80% → 0.8")
        void eightyPercent() {
            VolunteerProfile profile = new VolunteerProfile();
            profile.setAcceptanceRate(0.8);
            profile.setTotalDispatched(10);
            assertThat(scoringService.calcAcceptanceScore(profile)).isEqualTo(0.8);
        }
    }

    // ===== 配速匹配得分 =====

    @Nested
    @DisplayName("配速匹配得分计算")
    class PaceScoreTest {

        @Test
        @DisplayName("完全匹配 → 1.0")
        void exactMatch() {
            assertThat(scoringService.calcPaceScore(PacePreference.MODERATE, PacePreference.MODERATE))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("相差1级 → 0.6")
        void oneLevelDiff() {
            assertThat(scoringService.calcPaceScore(PacePreference.EASY, PacePreference.MODERATE))
                    .isEqualTo(0.6);
        }

        @Test
        @DisplayName("相差2级 → 0.2")
        void twoLevelDiff() {
            assertThat(scoringService.calcPaceScore(PacePreference.WALK_RUN, PacePreference.MODERATE))
                    .isEqualTo(0.2);
        }

        @Test
        @DisplayName("相差3级 → 0.0")
        void threeLevelDiff() {
            assertThat(scoringService.calcPaceScore(PacePreference.WALK_RUN, PacePreference.FAST))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("订单 NO_PREFERENCE → 0.5（中性）")
        void orderNoPreference() {
            assertThat(scoringService.calcPaceScore(PacePreference.NO_PREFERENCE, PacePreference.FAST))
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("志愿者 NO_PREFERENCE → 0.5（中性）")
        void volunteerNoPreference() {
            assertThat(scoringService.calcPaceScore(PacePreference.MODERATE, PacePreference.NO_PREFERENCE))
                    .isEqualTo(0.5);
        }
    }

    // ===== 时间重叠率 =====

    @Nested
    @DisplayName("时间重叠率计算")
    class TimeOverlapTest {

        @Test
        @DisplayName("无可用时间数据 → 默认 0.5")
        void noAvailability_defaultScore() {
            RunOrder order = createOrder();
            double overlap = scoringService.calcTimeOverlap(order, List.of());
            assertThat(overlap).isEqualTo(0.5);
        }

        @Test
        @DisplayName("完全覆盖 → 1.0")
        void fullCoverage() {
            RunOrder order = createOrder(); // 18:00-19:00
            VolunteerAvailableTime slot = createSlot(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(20, 0));
            double overlap = scoringService.calcTimeOverlap(order, List.of(slot));
            assertThat(overlap).isEqualTo(1.0);
        }

        @Test
        @DisplayName("部分重叠 30/60 分钟 → 0.5")
        void partialOverlap() {
            RunOrder order = createOrder(); // 18:00-19:00
            VolunteerAvailableTime slot = createSlot(DayOfWeek.MONDAY, LocalTime.of(18, 30), LocalTime.of(20, 0));
            double overlap = scoringService.calcTimeOverlap(order, List.of(slot));
            assertThat(overlap).isEqualTo(0.5);
        }

        @Test
        @DisplayName("无重叠 → 0.0")
        void noOverlap() {
            RunOrder order = createOrder(); // 18:00-19:00
            VolunteerAvailableTime slot = createSlot(DayOfWeek.MONDAY, LocalTime.of(20, 0), LocalTime.of(21, 0));
            double overlap = scoringService.calcTimeOverlap(order, List.of(slot));
            assertThat(overlap).isEqualTo(0.0);
        }

        @Test
        @DisplayName("不同星期 → 0.0")
        void differentDay() {
            RunOrder order = createOrder(); // Monday
            VolunteerAvailableTime slot = createSlot(DayOfWeek.TUESDAY, LocalTime.of(17, 0), LocalTime.of(20, 0));
            double overlap = scoringService.calcTimeOverlap(order, List.of(slot));
            assertThat(overlap).isEqualTo(0.0);
        }
    }

    // ===== 完整评分流程 =====

    @Nested
    @DisplayName("完整评分流程")
    class FullScoringTest {

        @Test
        @DisplayName("单候选人：评分正确计算")
        void singleCandidate() {
            RunOrder order = createOrder();
            order.setHasGuideDogThisRun(false);
            order.setPacePreference(PacePreference.MODERATE);

            List<Map<String, Object>> locations = List.of(
                    createLocation(1L, order.getStartLatitude() + 0.01, order.getStartLongitude() + 0.01) // ~1km
            );

            VolunteerProfile profile = createFullProfile(1L);
            Map<Long, VolunteerProfile> profiles = Map.of(1L, profile);

            // 完全覆盖的可用时间
            VolunteerAvailableTime slot = createSlot(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(20, 0));
            Map<Long, List<VolunteerAvailableTime>> availability = Map.of(1L, List.of(slot));

            List<ScoredCandidate> result = scoringService.scoreCandidates(
                    order, locations, profiles, availability, 5.0, 0.8);

            assertThat(result).hasSize(1);
            ScoredCandidate c = result.get(0);
            assertThat(c.volunteerId()).isEqualTo(1L);
            assertThat(c.totalScore()).isGreaterThan(0);
            assertThat(c.distanceScore()).isGreaterThan(0);
            assertThat(c.timeMatchScore()).isGreaterThan(0);
        }

        @Test
        @DisplayName("多候选人：按分数降序排列")
        void multipleCandidates_sorted() {
            RunOrder order = createOrder();
            order.setHasGuideDogThisRun(false);
            order.setPacePreference(PacePreference.MODERATE);

            // 志愿者1近、志愿者2远
            List<Map<String, Object>> locations = List.of(
                    createLocation(2L, order.getStartLatitude() + 0.05, order.getStartLongitude()), // ~5.5km
                    createLocation(1L, order.getStartLatitude() + 0.005, order.getStartLongitude())  // ~0.5km
            );

            Map<Long, VolunteerProfile> profiles = new HashMap<>();
            profiles.put(1L, createFullProfile(1L));
            profiles.put(2L, createFullProfile(2L));

            Map<Long, List<VolunteerAvailableTime>> availability = Map.of(
                    1L, List.of(createSlot(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(20, 0))),
                    2L, List.of(createSlot(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(20, 0)))
            );

            List<ScoredCandidate> result = scoringService.scoreCandidates(
                    order, locations, profiles, availability, 10.0, 0.8);

            assertThat(result).hasSize(2);
            // 近的志愿者分数更高
            assertThat(result.get(0).volunteerId()).isEqualTo(1L);
            assertThat(result.get(0).totalScore()).isGreaterThan(result.get(1).totalScore());
        }

        @Test
        @DisplayName("硬性过滤：距离超限被排除")
        void filterByDistance() {
            RunOrder order = createOrder();

            // 志愿者在 8km 外，阈值 5km
            List<Map<String, Object>> locations = List.of(
                    createLocation(1L, order.getStartLatitude() + 0.07, order.getStartLongitude())
            );

            Map<Long, VolunteerProfile> profiles = Map.of(1L, createFullProfile(1L));
            Map<Long, List<VolunteerAvailableTime>> availability = Map.of();

            List<ScoredCandidate> result = scoringService.scoreCandidates(
                    order, locations, profiles, availability, 5.0, 0.8);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("硬性过滤：导盲犬不兼容被排除")
        void filterByGuideDog() {
            RunOrder order = createOrder();
            order.setHasGuideDogThisRun(true);

            List<Map<String, Object>> locations = List.of(
                    createLocation(1L, order.getStartLatitude() + 0.01, order.getStartLongitude())
            );

            VolunteerProfile profile = createFullProfile(1L);
            profile.setAcceptsGuideDog(false);
            Map<Long, VolunteerProfile> profiles = Map.of(1L, profile);
            Map<Long, List<VolunteerAvailableTime>> availability = Map.of();

            List<ScoredCandidate> result = scoringService.scoreCandidates(
                    order, locations, profiles, availability, 5.0, 0.8);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("硬性过滤：注册未完成被排除")
        void filterByRegistrationStep() {
            RunOrder order = createOrder();

            List<Map<String, Object>> locations = List.of(
                    createLocation(1L, order.getStartLatitude() + 0.01, order.getStartLongitude())
            );

            VolunteerProfile profile = createFullProfile(1L);
            profile.setRegistrationStep(RegistrationStep.STEP_3_FACE_VERIFY);
            Map<Long, VolunteerProfile> profiles = Map.of(1L, profile);
            Map<Long, List<VolunteerAvailableTime>> availability = Map.of();

            List<ScoredCandidate> result = scoringService.scoreCandidates(
                    order, locations, profiles, availability, 5.0, 0.8);

            assertThat(result).isEmpty();
        }
    }

    // ===== 测试辅助方法 =====

    private RunOrder createOrder() {
        RunOrder order = new RunOrder();
        order.setStartLatitude(39.9);
        order.setStartLongitude(116.4);
        order.setStartAddress("朝阳公园南门");
        // Monday 18:00-19:00
        order.setPlannedStartTime(java.time.LocalDateTime.of(2026, 4, 6, 18, 0));
        order.setPlannedEndTime(java.time.LocalDateTime.of(2026, 4, 6, 19, 0));
        order.setPacePreference(PacePreference.MODERATE);
        order.setHasGuideDogThisRun(false);
        return order;
    }

    private VolunteerProfile createFullProfile(Long userId) {
        VolunteerProfile profile = new VolunteerProfile();
        profile.setUserId(userId);
        profile.setVerified(true);
        profile.setRegistrationStep(RegistrationStep.STEP_4_COMPLETED);
        profile.setAcceptsGuideDog(true);
        profile.setPaceRange(PacePreference.MODERATE);
        profile.setAvgRating(4.5);
        profile.setTotalRatings(10);
        profile.setAcceptanceRate(0.85);
        profile.setTotalDispatched(20);
        return profile;
    }

    private Map<String, Object> createLocation(Long userId, double lat, double lng) {
        Map<String, Object> loc = new HashMap<>();
        loc.put("userId", userId);
        loc.put("lat", lat);
        loc.put("lng", lng);
        loc.put("isOnline", true);
        return loc;
    }

    private VolunteerAvailableTime createSlot(DayOfWeek day, LocalTime start, LocalTime end) {
        VolunteerAvailableTime slot = new VolunteerAvailableTime();
        slot.setDayOfWeek(day.name());
        slot.setStartTime(start);
        slot.setEndTime(end);
        return slot;
    }
}

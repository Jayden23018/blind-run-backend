package com.example.demo.repository;

import com.example.demo.entity.IdVerifyStatus;
import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.VerificationStatus;
import com.example.demo.entity.VolunteerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolunteerProfileRepository extends JpaRepository<VolunteerProfile, Long> {
    Optional<VolunteerProfile> findByUserId(Long userId);
    List<VolunteerProfile> findByUserIdIn(Collection<Long> userIds);
    List<VolunteerProfile> findByIdVerifyStatus(IdVerifyStatus status);
    List<VolunteerProfile> findByVerificationStatus(VerificationStatus status);
    long countByRegistrationStep(RegistrationStep step);

    /**
     * 原子更新接单统计（C6 修复：native query + ROUND 消除浮点精度问题）
     *
     * 在执行时，total_dispatched / total_accepted 均为更新前的旧值，所以：
     * - 新 total_dispatched = total_dispatched + 1
     * - 新 total_accepted   = total_accepted + 1
     * - 新 acceptance_rate  = ROUND((old_accepted + 1) / (old_dispatched + 1), 4)
     *
     * 使用 1.0 强制浮点除法，ROUND 保留 4 位小数统一精度。
     */
    @Modifying
    @Query(value =
            "UPDATE volunteer_profile SET " +
            "  total_dispatched = total_dispatched + 1, " +
            "  total_accepted   = total_accepted + 1, " +
            "  acceptance_rate  = ROUND((total_accepted + 1) / (total_dispatched + 1.0), 4) " +
            "WHERE user_id = :userId",
            nativeQuery = true)
    void atomicIncrementAcceptStats(@Param("userId") Long userId);

    /**
     * 原子更新拒单统计（C6 修复：native query + ROUND）
     *
     * 拒单时 total_accepted 不变，新 acceptance_rate = old_accepted / (old_dispatched + 1)
     */
    @Modifying
    @Query(value =
            "UPDATE volunteer_profile SET " +
            "  total_dispatched = total_dispatched + 1, " +
            "  total_declined   = total_declined + 1, " +
            "  acceptance_rate  = ROUND(total_accepted / (total_dispatched + 1.0), 4) " +
            "WHERE user_id = :userId",
            nativeQuery = true)
    void atomicIncrementDeclineStats(@Param("userId") Long userId);

    /**
     * 原子更新超时统计（V1 修复：超时≠主动拒绝，走独立字段，不污染 acceptanceRate）
     *
     * 超时只递增 total_dispatched + total_timeout，不动 total_declined/total_accepted，
     * 因此 acceptance_rate 分子分母都不变——超时对志愿者接单率评分完全中性。
     */
    @Modifying
    @Query(value =
            "UPDATE volunteer_profile SET " +
            "  total_dispatched = total_dispatched + 1, " +
            "  total_timeout    = total_timeout + 1 " +
            "WHERE user_id = :userId",
            nativeQuery = true)
    void atomicIncrementTimeoutStats(@Param("userId") Long userId);

    /**
     * 原子更新评分（C6 修复：native query + ROUND）
     *
     * 公式：newAvg = ROUND((oldAvg * oldTotal + rating) / (oldTotal + 1), 2)
     * - COALESCE 处理首次评分时字段为 NULL 的情况
     * - avg_rating 保留 2 位小数（与前端显示精度一致）
     */
    @Modifying
    @Query(value =
            "UPDATE volunteer_profile SET " +
            "  avg_rating    = ROUND((COALESCE(avg_rating, 0.0) * COALESCE(total_ratings, 0) + :rating) " +
            "                       / (COALESCE(total_ratings, 0) + 1.0), 2), " +
            "  total_ratings = COALESCE(total_ratings, 0) + 1 " +
            "WHERE user_id = :userId",
            nativeQuery = true)
    void atomicUpdateRating(@Param("userId") Long userId, @Param("rating") double rating);
}

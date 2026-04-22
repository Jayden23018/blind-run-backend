package com.example.demo.dto;

/**
 * 派单评分结果 —— 记录单个志愿者的评分明细
 *
 * 各维度满分：距离40、时间25、评分20、接单率10、配速5，总分100
 */
public record ScoredCandidate(
        Long volunteerId,
        double totalScore,
        double distanceScore,
        double timeMatchScore,
        double ratingScore,
        double acceptanceRateScore,
        double paceMatchScore,
        double distanceKm
) implements Comparable<ScoredCandidate> {

    @Override
    public int compareTo(ScoredCandidate other) {
        // 按 totalScore 降序排列
        return Double.compare(other.totalScore, this.totalScore);
    }
}

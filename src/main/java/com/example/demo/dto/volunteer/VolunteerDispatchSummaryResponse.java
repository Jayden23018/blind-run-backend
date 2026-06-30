package com.example.demo.dto.volunteer;

import com.example.demo.dto.VolunteerAvailableTimeSlot;
import com.example.demo.entity.DispatchBlockReason;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 志愿者首页聚合响应 —— 一次返回首页所需的全部数据
 *
 * 对应端点：GET /api/volunteer/dispatch-summary（角色：VOLUNTEER）
 *
 * 【字段分组】
 *   1. 接单资格：canDispatch / notAvailableReasons / wantsDispatch
 *   2. 在线与位置：isOnline / lastLat / lastLng / lastLocationAt
 *   3. 覆盖范围与时段：coverageRadiusKm / isWithinServiceTime / availableTimeSlots
 *   4. 评分与统计：avgRating / totalRatings / totalDispatched / totalAccepted / totalDeclined / totalTimeout / acceptanceRate
 *   5. 订单：activeOrders / recentOrders
 *
 * 【null 语义】
 *   - canDispatch / wantsDispatch / isOnline / isWithinServiceTime / coverageRadiusKm / 所有 total* / totalRatings：
 *     始终非 null（原子类型或实体默认值）。
 *   - lastLat / lastLng / lastLocationAt：仅 isOnline=true 时有值，离线时为 null（Redis 30s TTL 过期，过期坐标不可信）。
 *   - avgRating：null 表示尚无评价。
 *   - acceptanceRate：null 表示尚无派单记录。
 *   - notAvailableReasons / availableTimeSlots / activeOrders / recentOrders：可为空 List（用 List.of()，不会是 null）。
 */
@Data
@AllArgsConstructor
public class VolunteerDispatchSummaryResponse {

    // ===== 1. 接单资格 =====

    /** 系统判定当前是否可被派单（verified + registrationStep==STEP_4_COMPLETED + wantsDispatch + 在线） */
    private boolean canDispatch;
    /** 不可接单的结构化原因（canDispatch=true 时为空 List） */
    private List<DispatchBlockReason> notAvailableReasons;
    /** 用户接单开关状态（用户意愿，区别于 canDispatch 的系统判定） */
    private boolean wantsDispatch;

    // ===== 2. 在线与位置 =====

    /** WebSocket 在线状态（Redis 有 isOnline=true 记录） */
    @JsonProperty("isOnline")
    private boolean isOnline;
    /** 最近位置纬度（地图范围圆心，离线时为 null） */
    private Double lastLat;
    /** 最近位置经度（地图范围圆心，离线时为 null） */
    private Double lastLng;
    /** 最近位置更新时间（离线时为 null） */
    private java.time.LocalDateTime lastLocationAt;

    // ===== 3. 覆盖范围与可服务时段 =====

    /** 可接单覆盖圆半径（km，当前固定取全局 app.matching.max-distance-km=10） */
    private int coverageRadiusKm;
    /** 当前是否落在可服务时段内（后端按今天周几+当前时间计算，仅影响 UI 提示，不阻断接单） */
    @JsonProperty("isWithinServiceTime")
    private boolean isWithinServiceTime;
    /** 可服务时间模板（按周几+时段，前端渲染完整时段表） */
    private List<VolunteerAvailableTimeSlot> availableTimeSlots;

    // ===== 4. 评分与统计 =====

    /** 平均评分 1.0-5.0（null=无评价） */
    private Double avgRating;
    /** 累计评价数 */
    private int totalRatings;
    /** 累计被派单次数 */
    private int totalDispatched;
    /** 累计接单次数（点了 ACCEPT 就算，含接了没跑完的） */
    private int totalAccepted;
    /** 累计完成订单次数（订单走到 COMPLETED 才算，区别于 totalAccepted） */
    private int totalCompleted;
    /** 累计拒单次数（仅 DECLINE，不含超时） */
    private int totalDeclined;
    /** 累计响应超时次数（不计入 acceptanceRate 分母） */
    private int totalTimeout;
    /** 接单率 0.0-1.0（null=无派单记录） */
    private Double acceptanceRate;

    // ===== 5. 订单列表 =====

    /** 当前活跃订单（全量，通常 ≤1 条） */
    private List<VolunteerDispatchActiveOrder> activeOrders;
    /** 近期服务记录（最近 5 条，按创建时间倒序） */
    private List<VolunteerDispatchRecentOrder> recentOrders;
}

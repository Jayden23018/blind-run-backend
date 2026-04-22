package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑步订单实体类 —— 对应数据库中的 run_order 表
 *
 * 【这个表存什么？】
 * 盲人用户下单陪跑的所有信息：在哪里跑、什么时候跑、状态如何、哪个志愿者接了单。
 *
 * 【字段说明】
 * blindUser      下单的盲人用户
 * volunteer      接单的志愿者（下单时为空，接单后填入）
 * startLatitude  起跑点纬度（用于距离匹配计算）
 * startLongitude 起跑点经度
 * startAddress   起跑点文字描述（如"朝阳公园南门"）
 * plannedStartTime  计划开始时间
 * plannedEndTime    计划结束时间
 * status         订单状态（见 OrderStatus 枚举）
 * acceptedAt     志愿者接单时间
 */
@Data
@Entity
@Table(name = "run_order")
public class RunOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 下单的盲人用户 —— 多对一关系
     * 多个订单可以属于同一个盲人用户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blind_user_id", nullable = false)
    private User blindUser;

    /**
     * 接单的志愿者 —— 下单时为 null，志愿者接单后填入
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volunteer_id")
    private User volunteer;

    /** 起跑点纬度 */
    @Column(nullable = false)
    private Double startLatitude;

    /** 起跑点经度 */
    @Column(nullable = false)
    private Double startLongitude;

    /** 起跑点文字描述（前端传入，如"朝阳公园南门"） */
    @Column(nullable = false)
    private String startAddress;

    /** 计划开始时间 */
    @Column(nullable = false)
    private LocalDateTime plannedStartTime;

    /** 计划结束时间 */
    @Column(nullable = false)
    private LocalDateTime plannedEndTime;

    /** 预计跑步时长（分钟） */
    @Column(name = "expected_duration_minutes")
    private Integer expectedDurationMinutes;

    /** 本次配速偏好（覆盖档案默认值） */
    @Enumerated(EnumType.STRING)
    @Column(name = "pace_preference", length = 20)
    private PacePreference pacePreference;

    /** 路线偏好 */
    @Enumerated(EnumType.STRING)
    @Column(name = "route_preference", length = 20)
    private RoutePreference routePreference = RoutePreference.NO_PREFERENCE;

    /** 路线备注 */
    @Column(name = "route_notes", length = 200)
    private String routeNotes;

    /** 本次是否携带导盲犬（null=跟随档案默认值） */
    @Column(name = "has_guide_dog_this_run")
    private Boolean hasGuideDogThisRun;

    /** 本次订单一次性备注 */
    @Column(name = "special_notes", length = 200)
    private String specialNotes;

    /**
     * 订单状态 —— 用枚举类型存储
     * @Enumerated(EnumType.STRING) 表示在数据库中存储枚举的名称字符串（如 "PENDING_MATCH"），
     * 而不是序号（0, 1, 2...），这样更直观、更安全。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.PENDING_MATCH;

    /** 志愿者接单时间（下单时为空） */
    private LocalDateTime acceptedAt;

    /** 订单完成时间（服务结束时填入） */
    private LocalDateTime finishedAt;

    /** 取消方：BLIND 或 VOLUNTEER（取消时填入） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CancelledBy cancelledBy;

    /** 订单创建时间 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 订单更新时间 */
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 —— 防止并发接单 */
    @Version
    private Long version;

    /** 累计重新匹配次数 */
    @Column(name = "rematch_count")
    private Integer rematchCount = 0;

    /** 最近一次进入重新匹配的时间 */
    @Column(name = "last_rematch_at")
    private LocalDateTime lastRematchAt;

    /** 重新匹配下次提醒时间（定时轮询用） */
    @Column(name = "rematch_notify_at")
    private LocalDateTime rematchNotifyAt;

    /** 匹配超时下次提醒时间（创建订单时写入，定时轮询用） */
    @Column(name = "match_notify_at")
    private LocalDateTime matchNotifyAt;

    /** 匹配超时已提醒次数（超过最大次数后自动取消） */
    @Column(name = "match_notify_count")
    private Integer matchNotifyCount = 0;

    /** 是否已发送超时挂起通知（超过结束时间1小时后提醒志愿者） */
    @Column(name = "overdue_notified")
    private Boolean overdueNotified = false;

    // ========== 串行派单状态字段 ==========

    /** 派单轮次（0=未开始, 1=5km, 2=10km, 3=20km） */
    @Column(name = "dispatch_round")
    private Integer dispatchRound = 0;

    /** 当前正在等待回应的志愿者 ID（镜像 Redis dispatch_current，用于崩溃恢复） */
    @Column(name = "dispatch_current_volunteer_id")
    private Long dispatchCurrentVolunteerId;

    /** 派单流程开始时间，用于计算总超时 */
    @Column(name = "dispatch_started_at")
    private LocalDateTime dispatchStartedAt;

    /**
     * JPA 生命周期回调 —— 在插入数据库之前自动执行
     * 设置创建时间和初始状态
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = OrderStatus.PENDING_MATCH;
        }
    }

    /**
     * JPA 生命周期回调 —— 在更新数据库之前自动执行
     * 更新修改时间
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

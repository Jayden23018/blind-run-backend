package com.example.demo.entity;

/**
 * 志愿者「不可接单原因」结构化枚举
 *
 * 用于志愿者首页聚合接口（GET /api/volunteer/dispatch-summary）的 notAvailableReasons 字段。
 * 前端按枚举值给出精确引导，而非一句模糊的「暂时无法接单」。
 *
 * 【引导优先级（前端建议处理顺序）】
 *   REGISTRATION_INCOMPLETE > NOT_VERIFIED > DISPATCH_DISABLED > OFFLINE
 *   先解决资质 → 再认证 → 再开接单开关 → 最后上线定位
 *
 * 注意：可服务时段（isWithinServiceTime）不纳入此枚举，也不计入 canDispatch 判定 ——
 * 多数志愿者未填写可用时段，若硬拦会导致大多数人无法接单（与 ScoringService 语义一致：
 * 无可用时段记录时跳过时间过滤，不惩罚）。
 */
public enum DispatchBlockReason {
    /** 用户主动关闭了接单开关（wantsDispatch=false） */
    DISPATCH_DISABLED,

    /** 未通过资质认证（verified=false） */
    NOT_VERIFIED,

    /** 注册流程未完成（registrationStep != STEP_4_COMPLETED） */
    REGISTRATION_INCOMPLETE,

    /** 当前离线，Redis 无在线位置记录（getVolunteerLocation 返回 null） */
    OFFLINE
}

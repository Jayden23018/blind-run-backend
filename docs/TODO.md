# 助盲跑后端 — 待办事项（功能路线图）

> **分工说明**：本文件记**功能开发路线图**（要做什么新功能）+ 技术债。
> 缺陷追踪见 `ISSUES.md`，版本发布见 `CHANGELOG.md`。**已完成的功能不再在此记录**（见 CHANGELOG）。

**最后更新**：2026-06-17

---

## P1 — 功能完善

### 隐私号码通话
- **现状**: `PrivateNumberService` 默认 mock（`aliyun.private-number.enabled=false` 时返回假号码 `170xxxxxxxx`，状态 CONNECTED）
- **目标**: 开通阿里云号码隐私保护，分配虚拟号码实现双向匿名通话
- **需要开通**: [阿里云号码隐私保护](https://www.aliyun.com/product/vms) | [控制台](https://pls.console.aliyun.com)
- **配置**: `aliyun.private-number.enabled=true` + 号码池
- **注意**: 需企业实名认证，按月租 + 通话分钟计费

### `NO_VOLUNTEER` 订单终态
- **现状**: `OrderStatus.NO_VOLUNTEER` 枚举存在但未完整使用，超时无人接单的订单未自动关闭
- **目标**: 匹配超时 + 3 次提醒后，订单自动转为 `NO_VOLUNTEER` 终态并通知盲人用户
- **涉及**: `TimeoutScheduler`, `OrderService`

---

## P2 — 增强体验

### App 推送通知
- **现状**: `NotificationChannel.APP_PUSH` 和 `NotifyType.APP_PUSH` 枚举已定义，无实现
- **目标**: WebSocket 断线时通过推送服务通知用户（新订单、紧急求助、订单状态变更）
- **需要开通**: 极光推送 / 个推 / Firebase Cloud Messaging

### AI 语音外呼
- **现状**: `NotifyType.AI_VOICE_CALL` 枚举已定义，无实现
- **目标**: 紧急事件升级时，系统自动拨打紧急联系人电话并播放语音通知
- **需要开通**: 阿里云智能外呼 / 容联云

### AI 紧急事件自动检测
- **现状**: 实体预留了相关字段，无实现
- **目标**: 通过传感器数据或行为模式自动检测异常，主动触发紧急求助
- **说明**: 需要传感器数据接入 + 算法模型，属于高级功能

---

## 已知技术债务

| 项目 | 说明 |
|------|------|
| `POST /api/volunteer/location` | `@Deprecated`（前端用 WebSocket 上报），但**集成测试 setUp（testHelper.updateVolunteerLocation）仍走 REST**，保留不删 |

> 已清理（2026-06-17）：`NotificationService` 废弃 stub（sendOrderStatusChange/sendSms/sendSmsToUser，前序已删）、`OrderLifecycleService.acceptOrderWithRetry/rejectOrder`（B2 后无生产调用，已删）、`POST /api/orders/{id}/accept`、`/reject`（前端已迁移 /respond，已下线 + TestHelper/OrderPermissionTest 适配）。

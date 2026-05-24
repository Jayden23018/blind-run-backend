# 助盲跑后端 — 待办事项

> 最后更新: 2026-04-22

---

## P0 — 影响核心功能 / 数据安全

~~### 1. 文件云存储（阿里云 OSS）~~ ✅ 已完成（2026-04-22）

---

## P1 — 功能完善

### 2. 隐私号码通话

- **现状**: `PrivateNumberService` 返回假号码 `170xxxxxxxx`，状态 `CONNECTED`，不真正接通电话
- **目标**: 接入阿里云号码隐私保护，分配虚拟号码实现双向匿名通话
- **涉及代码**: `PrivateNumberService` → 新建 `AliyunPrivateNumberService`
- **需要开通**: [阿里云号码隐私保护](https://www.aliyun.com/product/vms) | [控制台](https://pls.console.aliyun.com)
- **配置项**: `aliyun.private-number.enabled=true` + 号码池配置
- **注意**: 需企业实名认证，按月租 + 通话分钟计费

### 3. `NO_VOLUNTEER` 订单终态

- **现状**: `OrderStatus.NO_VOLUNTEER` 枚举存在但未完整使用，超时无人接单的订单未自动关闭
- **目标**: 匹配超时 + 3 次提醒后，订单自动转为 `NO_VOLUNTEER` 终态并通知盲人用户
- **涉及代码**: `TimeoutScheduler`, `OrderService`

~~### 4. 匹配评分体系（配速软性降权）~~ ✅ 已完成（2026-04-22）→ `ScoringService` 5维评分 + `DispatchService` 串行派单

---

## P2 — 增强体验

### 5. App 推送通知

- **现状**: `NotificationChannel.APP_PUSH` 和 `NotifyType.APP_PUSH` 枚举已定义，无实现
- **目标**: WebSocket 断线时通过推送服务通知用户（新订单、紧急求助、订单状态变更）
- **需要开通**: 极光推送 / 个推 / Firebase Cloud Messaging

### 6. AI 语音外呼

- **现状**: `NotifyType.AI_VOICE_CALL` 枚举已定义，无实现
- **目标**: 紧急事件升级时，系统自动拨打紧急联系人电话并播放语音通知
- **需要开通**: 阿里云智能外呼 / 容联云

### 7. AI 紧急事件自动检测

- **现状**: 实体预留了相关字段，无实现
- **目标**: 通过传感器数据或行为模式自动检测异常，主动触发紧急求助
- **说明**: 需要传感器数据接入 + 算法模型，属于高级功能

---

## 已完成

| 日期 | 项目 | 说明 |
|------|------|------|
| 2026-04-19 | 紧急联系人短信通知 | 已迁移到 Dysmsapi 自定义签名+模板，支持紧急提醒/联系人被添加/紧急解除 4 种模板 |
| 2026-04-19 | 人脸验证（志愿者身份核验） | 已接入阿里云金融级实人认证 `ContrastFaceVerify`（`ID_MIN`），不再 Stub |
| 2026-04-21 | 订单特殊需求字段 | 新增配速/路线/导盲犬/时长/备注字段，盲人档案新增视力/牵引/聊天偏好，匹配新增导盲犬硬性过滤 |
| 2026-04-22 | OSS 文件云存储 | `OssFileStorageService` 接入阿里云 OSS，通过 `app.storage.type` 切换本地/OSS，presigned URL 访问 |
| 2026-04-22 | 串行派单 | `DispatchService` + `ScoringService` + `DispatchScheduler`：5维评分、3轮距离扩展、30s超时轮转 |
| 2026-04-22 | Aliyun 人脸认证完善 | `AliyunIdVerifyService` 添加 Model=NO_LIVENESS/Crop=T、照片自动压缩(<1MB)、错误码中文映射 |
| 2026-04-22 | 盲人 WebSocket | `BlindWebSocketHandler` 支持位置上报 + 获取志愿者位置 REST 接口 |

---

## 已知技术债务

| 项目 | 说明 |
|------|------|
| `NotificationService.sendOrderStatusChange()` | 标记 `@Deprecated`，应迁移到 `sendNotification()` 后删除 |
| `NotificationService.sendSms()` / `sendSmsToUser()` | 标记 `@Deprecated`，已替换为模板短信方法，待确认无调用后删除 |
| `VolunteerSessionRegistry` | 已删除，不要恢复，统一使用 `UnifiedSessionRegistry` |
| `POST /api/volunteer/location` | 标记 `@Deprecated`，保留兼容，后续可移除 |
| ReviewTest TC-REVIEW-03 | Flaky test，偶现失败，与代码变更无关 |

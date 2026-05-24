# 前端变更说明 v1.1.0

> 后端版本: 1.1.0 | 日期: 2026-04-22 | 分支: feat/aliyun-id-verify-blind-websocket

---

## 一、核心变更概览

| 模块 | 变更 | 影响范围 |
|------|------|----------|
| 串行派单 | 匹配流程从"群推"改为"串行派单" | 志愿者接单页面 |
| OSS 存储 | 文件上传后返回 presigned URL（有效期1小时） | 身份证上传、人脸照片 |
| 人脸认证 | 照片自动压缩，服务端比对 | 志愿者注册 Step3 |
| 订单字段 | 新增配速/路线/导盲犬/备注等字段 | 盲人下单、订单详情 |
| 盲人档案 | 新增视力/牵引/聊天/默认配速 | 盲人资料编辑页 |
| 志愿者档案 | 新增导盲犬/配速范围 | 志愿者资料编辑页 |
| 盲人 WebSocket | 新增盲人 WebSocket 端点 | 实时位置功能 |

---

## 二、接口变更

### 2.1 新增接口

#### `POST /api/orders/{id}/respond` — 志愿者响应派单（新增，推荐使用）

```json
// 请求体
{
  "action": "ACCEPT"    // 或 "DECLINE"
}

// 成功响应
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": { "orderId": 1, "status": "PENDING_ACCEPT" }
}
```

> 旧接口 `POST /{id}/accept` 和 `POST /{id}/reject` 仍可用（@Deprecated），建议迁移到新接口。

#### `GET /api/blind/volunteer-location` — 查询志愿者实时位置

```json
// 响应（仅在订单状态为 DRIVER_EN_ROUTE 或 DRIVER_ARRIVED 时返回）
{
  "success": true,
  "data": {
    "volunteerId": 5,
    "lat": 22.5432,
    "lng": 114.0580,
    "lastUpdated": "2026-04-22T10:30:00"
  }
}
```

#### `WS /ws/blind?token=<jwt>` — 盲人 WebSocket（新增端点）

发送消息格式：
```json
{ "type": "LOCATION_UPDATE", "lat": 22.5431, "lng": 114.0579 }
{ "type": "PING" }
```

接收消息格式与志愿者 WebSocket 一致（包含 `ttsText` 和 `priority` 字段）。

---

### 2.2 请求字段变更

#### `POST /api/orders` — 创建订单（新增字段）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `expectedDurationMinutes` | Integer | 否 | 预计跑步时长（10-300分钟） |
| `pacePreference` | String(enum) | 否 | 本次配速偏好，覆盖档案默认值。值：`EASY`/`MODERATE`/`FAST`/`WALK_RUN`/`NO_PREFERENCE` |
| `routePreference` | String(enum) | 否 | 路线偏好。值：`STREET`/`PARK_TRAIL`/`TRACK`/`NO_PREFERENCE` |
| `routeNotes` | String(200) | 否 | 路线备注 |
| `hasGuideDogThisRun` | Boolean | 否 | 本次是否携带导盲犬（null=使用档案默认值） |
| `specialNotes` | String(200) | 否 | 本次订单一次性备注 |

#### `PUT /api/blind/profile` — 盲人资料更新（新增字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `visionLevel` | String(enum) | 视力状况：`LOW_VISION`/`TOTAL_BLIND` |
| `hasGuideDog` | Boolean | 是否常带导盲犬 |
| `tetherPreference` | String(enum) | 牵引方式偏好：`TETHER_ROPE`/`ARM_HOLD`/`VERBAL_ONLY` |
| `chatPreference` | String(enum) | 聊天偏好：`PREFER_CHAT`/`PREFER_QUIET`/`NO_PREFERENCE` |
| `defaultPace` | String(enum) | 默认配速：`EASY`/`MODERATE`/`FAST`/`WALK_RUN`/`NO_PREFERENCE` |

#### `PUT /api/volunteer/profile` — 志愿者资料更新（新增字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `acceptsGuideDog` | Boolean | 是否接受携带导盲犬的订单 |
| `paceRange` | String(enum) | 可适应配速：`EASY`/`MODERATE`/`FAST`/`WALK_RUN`/`NO_PREFERENCE` |

---

### 2.3 响应字段变更

#### `GET /api/orders/{id}` — 订单详情（新增字段）

```json
{
  "orderId": 1,
  "status": "PENDING_MATCH",
  "startAddress": "深圳湾公园",
  "plannedStart": "2026-04-22T08:00:00",
  "plannedEnd": "2026-04-22T09:00:00",
  "volunteerPhone": "138****1234",
  "acceptedAt": null,
  "createdAt": "2026-04-22T07:30:00",

  "expectedDurationMinutes": 60,
  "pacePreference": "MODERATE",
  "routePreference": "PARK_TRAIL",
  "routeNotes": "沿着海边跑",
  "hasGuideDogThisRun": false,
  "specialNotes": "请带水",

  "visionLevel": "TOTAL_BLIND",
  "tetherPreference": "TETHER_ROPE",
  "chatPreference": "PREFER_CHAT"
}
```

#### `GET /api/blind/profile` — 盲人资料（新增字段）

```json
{
  "name": "张三",
  "runningPace": "6min/km",
  "specialNeeds": "需要语音引导",
  "verifyStatus": "VERIFIED",

  "visionLevel": "TOTAL_BLIND",
  "hasGuideDog": false,
  "tetherPreference": "TETHER_ROPE",
  "chatPreference": "PREFER_CHAT",
  "defaultPace": "MODERATE"
}
```

---

## 三、行为变更（需要前端适配）

### 3.1 串行派单 — 接单流程变化

**之前**: 多名志愿者同时收到推送，先到先得（`POST /{id}/accept`）

**现在**: 系统按评分从高到低逐个推送给志愿者，每人 30 秒响应时间：
- 收到推送的志愿者看到"接单/跳过"按钮
- 调用 `POST /{id}/respond {action: "ACCEPT"}` 接单
- 调用 `POST /{id}/respond {action: "DECLINE"}` 跳过
- 30 秒不响应自动轮转到下一位

**前端建议**:
- 订单状态仍为 `PENDING_MATCH`（不是 `PENDING_ACCEPT`）
- WebSocket 推送消息类型不变，但只有当前被派单的志愿者会收到
- 建议增加倒计时 UI（30秒）

### 3.2 文件上传 — URL 变为临时链接

**之前**: 上传后返回的 URL 是固定路径（如 `/tmp/blindrun-uploads/xxx.jpg`）

**现在**: 上传后返回 object key，获取实际访问 URL 需调用 `fileStorageService.getUrl(key)` 生成 presigned URL（有效期1小时）

**前端影响**: 身份证照片、人脸照片的显示 URL 会定期变化（每小时刷新），前端不需要缓存 URL。

### 3.3 志愿者注册 — 人脸认证

**之前**: Step3 是 Stub 实现，始终返回通过

**现在**: Step3 调用阿里云 `ContrastFaceVerify`，真正比对人脸。可能返回不通过：
- 需要前端展示失败原因（`message` 字段）
- 允许用户重新拍照提交
- 照片会在服务端自动压缩，前端无需处理

---

## 四、验证流程

### 4.1 串行派单验证

```
前置条件：Redis 运行中，至少 2 名已注册志愿者在线

1. 盲人用户创建订单 POST /api/orders
2. 观察 WebSocket：仅 1 名志愿者收到派单推送
3. 志愿者点击"接单" POST /api/orders/{id}/respond {action: "ACCEPT"}
4. 验证订单状态变为 IN_PROGRESS（或 PENDING_ACCEPT）
5. 如果志愿者点击"跳过"或 30 秒不响应，验证下一位志愿者收到推送
```

### 4.2 新订单字段验证

```
1. 盲人下单时填写：配速偏好(MODERATE) + 路线偏好(PARK_TRAIL) + 备注
2. 志愿者查看订单详情 GET /api/orders/{id}
3. 验证响应中包含所有新增字段
4. 验证盲人档案信息也一同返回（visionLevel/tetherPreference/chatPreference）
```

### 4.3 盲人档案验证

```
1. PUT /api/blind/profile 提交新增字段
2. GET /api/blind/profile 验证返回值
3. 验证下单时 defaultPace 作为默认值使用
```

### 4.4 人脸认证验证

```
1. 志愿者注册到 Step3
2. 上传清晰正面人脸照片 POST /api/volunteer/registration/step3/face-verify
3. 验证返回 passed=true 或 passed=false + 失败原因 message
4. 如果失败，更换照片重试
```

### 4.5 盲人 WebSocket 验证

```
1. 盲人用户连接 ws://host:port/ws/blind?token=<jwt>
2. 发送 {"type":"LOCATION_UPDATE","lat":22.54,"lng":114.05}
3. 志愿者在跑中(DRIVER_EN_ROUTE)时，盲人调用 GET /api/blind/volunteer-location
4. 验证返回志愿者经纬度
```

### 4.6 枚举值参考

| 枚举 | 值 | 说明 |
|------|----|------|
| PacePreference | `EASY` / `MODERATE` / `FAST` / `WALK_RUN` / `NO_PREFERENCE` | 配速偏好 |
| RoutePreference | `STREET` / `PARK_TRAIL` / `TRACK` / `NO_PREFERENCE` | 路线偏好 |
| VisionLevel | `LOW_VISION` / `TOTAL_BLIND` | 视力状况 |
| TetherPreference | `TETHER_ROPE` / `ARM_HOLD` / `VERBAL_ONLY` | 牵引方式 |
| ChatPreference | `PREFER_CHAT` / `PREFER_QUIET` / `NO_PREFERENCE` | 聊天偏好 |
| RespondAction | `ACCEPT` / `DECLINE` | 派单响应 |

---

## 五、已删除/废弃的接口

| 接口 | 状态 | 替代方案 |
|------|------|----------|
| `POST /api/orders/{id}/accept` | **已删除** | `POST /{id}/respond {action: "ACCEPT"}` |
| `POST /api/orders/{id}/reject` | **已删除** | `POST /{id}/respond {action: "DECLINE"}` |
| `POST /api/volunteer/verification` | **已删除** | `POST /api/volunteer/registration/step2/id-card`（走正式审核流程） |
| `GET /api/volunteer/verification/status` | **已删除** | `GET /api/volunteer/registration/status`（返回完整注册进度） |
| `POST /api/volunteer/location` | **已删除** | WebSocket `LOCATION_UPDATE` 消息 |
| `POST /api/blind/location` | **已删除** | WebSocket `/ws/blind` `LOCATION_UPDATE` 消息 |

> 所有废弃接口已完全移除，前端必须使用替代方案。

---

## 六、无需前端改动的部分

以下变更仅在后端发生，前端无需调整：
- OSS 存储切换（后端自动处理，接口不变）
- 照片压缩（服务端自动压缩，前端无需预处理）
- 乐观锁重试（后端自动重试，前端无感知）
- DispatchScheduler 定时轮询（后端后台任务）
- 主备端点切换（后端自动 fallback）

# 助盲跑后端 — 前端开发对接指南

> ## ⚠️ v1.5.0 前端联调升级说明（2026-06-22，必读）
>
> 本次后端修复了前端联调反馈的 6 项问题，含 **API 契约变更**，前端需配合升级。完整说明见 [CHANGELOG 1.5.0](./CHANGELOG.md)。
>
> | 变更 | 前端需做的事 |
> |------|-------------|
> | **统一错误结构** | 所有错误响应统一为 `{success, code, errorCode, message}`。验证码错误不再返回 `{"error":...}`。用 `errorCode` 字段做程序化分支（见下方错误码表）。 |
> | **志愿者可服务状态** | `GET /api/volunteer/profile` 响应新增 `wantsDispatch`（boolean）字段，用于展示开关状态。`PUT /api/volunteer/profile` 传 `wantsDispatch` 切换。**关闭时接单会被拒（403 + `VOLUNTEER_NOT_AVAILABLE`）**。 |
> | **预约提前量** | 创建订单时 `plannedStartTime` 必须 ≥ 当前时间 + 30 分钟，否则返回 **HTTP 422** + `errorCode: APPOINTMENT_TOO_SOON`。前端表单应做前置提示。 |
> | **紧急联系人电话明文** | `GET /api/users/{me}/emergency-contacts` 返回**明文**电话（不再掩码）。前端展示层需自行脱敏。`PUT` 更新改 PATCH 语义：未传 `phone` 字段保留原值（不必每次回传）。 |
> | **状态机语义（v2 更新，见 3.7 节）** | ⚠️ 此处描述已过时——志愿者 `/arrived` 不再可直接 `/finish`。新增 `POST /{id}/start-service`（`DRIVER_ARRIVED → IN_PROGRESS`），`/finish` 仅接受 `IN_PROGRESS`。详见「3.7 订单管理」。 |
>
> ### 错误码表（errorCode）
>
> | errorCode | HTTP | 含义 |
> |-----------|------|------|
> | `INVALID_VERIFICATION_CODE` | 400 | 验证码错误或已过期 |
> | `PHONE_FORMAT_INVALID` | 400 | 手机号格式不正确 |
> | `USER_NOT_FOUND` | 404 | 用户不存在 |
> | `VOLUNTEER_NOT_AVAILABLE` | 403 | 志愿者已关闭可服务状态，可浏览但不能接单 |
> | `VOLUNTEER_NOT_REGISTERED` | 403 | 志愿者注册流程未完成 |
> | `VOLUNTEER_NOT_VERIFIED` | 403 | 志愿者证件审核未通过 |
> | `ORDER_ALREADY_ACCEPTED` | 409 | 订单已被他人接单 / 状态不允许接单 |
> | `ORDER_DISPATCH_MISMATCH` | 409 | 该订单当前未派送给您 |
> | `ORDER_CONCURRENT_CONFLICT` | 409 | 订单并发冲突，请稍后重试 |
> | `APPOINTMENT_TOO_SOON` | 422 | 预约开始时间距当前时间不足 30 分钟 |

---

## 一、基础信息

| 项目 | 地址 |
|------|------|
| API 基地址 | `http://47.114.113.171` |
| Swagger 文档 | `http://47.114.113.171/swagger-ui/index.html` |
| API JSON | `http://47.114.113.171/v3/api-docs` |
| WebSocket | `ws://47.114.113.171/ws/volunteer?token=<jwt_token>` |
| 盲人 WebSocket | `ws://47.114.113.171/ws/blind?token=<jwt_token>` |

> 所有需要认证的接口，在请求头加上：`Authorization: Bearer <token>`

---

## 二、认证流程

### 2.1 短信验证码登录

```
步骤1: POST /api/auth/send-code     → 发送验证码到手机
步骤2: POST /api/auth/verify-code   → 验证码登录，获取 JWT token
步骤3: GET  /api/auth/me            → 获取当前用户信息
```

#### 发送验证码
```json
// POST /api/auth/send-code
// 请求
{ "phone": "13800138000" }

// 响应
{ "success": true, "message": "验证码已发送" }
```

#### 验证码登录
```json
// POST /api/auth/verify-code
// 请求
{ "phone": "13800138000", "code": "123456" }

// 响应（成功）
{ "token": "eyJhbGciOiJIUzUxMiJ9...", "userId": 1, "role": "UNSET" }

// 响应（失败：验证码错误，HTTP 400）
{ "success": false, "code": 400, "errorCode": "INVALID_VERIFICATION_CODE", "message": "验证码错误或已过期" }
```

#### 客服登录（独立系统）
```json
// POST /api/cs/auth/login
// 请求
{ "username": "admin", "password": "admin123" }

// 响应
{ "token": "eyJhbGciOiJIUzUxMiJ9...", "role": "ADMIN" }
```

### 2.2 用户角色

新用户登录后角色为 `UNSET`，需要设置角色：

```json
// POST /api/user/role
// 请求
{ "role": "BLIND" }        // 或 "VOLUNTEER"

// 响应
{ "success": true, "message": "角色已设置为 BLIND" }
```

**角色说明**：
- `UNSET` — 新用户，未选择角色
- `BLIND` — 盲人用户，可创建订单
- `VOLUNTEER` — 志愿者，可接单

---

## 三、全部 API 接口

### 3.1 认证相关（无需 token）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/send-code` | 发送短信验证码 |
| POST | `/api/auth/verify-code` | 验证码登录 |
| POST | `/api/cs/auth/login` | 客服账号密码登录 |

### 3.2 用户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/auth/me` | 获取当前用户信息 |
| POST | `/api/user/role` | 设置角色（BLIND/VOLUNTEER） |
| GET | `/api/users/{id}` | 获取用户信息（只能查自己） |
| DELETE | `/api/users/{id}` | 删除账号（软删除） |

### 3.3 盲人用户

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/blind/profile` | 获取盲人资料 |
| PUT | `/api/blind/profile` | 更新盲人资料 |
| POST | `/api/blind/location` | 上报盲人位置 |
| GET | `/api/blind/volunteer-location` | 查询志愿者位置（REST 降级） |

**BlindProfileUpdateRequest**:
```json
{
  "name": "李明",              // 必填，2-50字符
  "runningPace": "6:00",      // 可选，最多20字符
  "specialNeeds": "需要导盲犬"  // 可选，最多500字符
}
```

### 3.4 紧急联系人（BLIND 角色）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/users/{userId}/emergency-contacts` | 获取联系人列表 |
| POST | `/api/users/{userId}/emergency-contacts` | 添加联系人 |
| PUT | `/api/users/{userId}/emergency-contacts/{contactId}` | 修改联系人 |
| DELETE | `/api/users/{userId}/emergency-contacts/{contactId}` | 删除联系人 |
| PUT | `/api/users/{userId}/emergency-contacts/{contactId}/set-primary` | 设为主要联系人 |

**EmergencyContactRequest**:
```json
{
  "name": "张三",              // 必填，最多50字符
  "phone": "13900139000",     // 必填，最多20字符
  "relationship": "家人",      // 可选，最多20字符
  "isPrimary": true           // 可选，默认false
}
```

**EmergencyContactResponse**（手机号脱敏）:
```json
{
  "id": 1,
  "name": "张三",
  "phone": "139****9000",
  "relationship": "家人",
  "isPrimary": true
}
```

> **规则**: 每个用户 1~5 个联系人；第一个自动设为主要；至少保留 1 个不能全删

### 3.5 志愿者注册（4步流程）

注册流程：`BASIC_INFO → ID_UPLOAD → FACE_VERIFY → TRAINING → COMPLETED`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/volunteer/registration/status` | 获取注册进度 |
| POST | `/api/volunteer/registration/step1` | 提交基本信息 |
| POST | `/api/volunteer/registration/step2/id-card` | 上传身份证（multipart） |
| POST | `/api/volunteer/registration/step3/face-verify/init` | 人脸验证 |
| GET | `/api/volunteer/registration/training/courses` | 获取培训课程列表 |
| POST | `/api/volunteer/registration/training/progress` | 提交学习进度 |
| GET | `/api/volunteer/registration/training/quiz/{courseId}` | 获取测验题目 |
| POST | `/api/volunteer/registration/training/quiz/answer` | 提交测验答案 |

**Step1 BasicInfoRequest**:
```json
{
  "name": "王五",
  "phone": "13800138000",
  "runningExperience": "3年跑步经验",
  "hasGuidedBefore": true,
  "emergencyExperience": "有急救证书"
}
```

**Step2 上传身份证**（multipart/form-data）:
```
POST /api/volunteer/registration/step2/id-card
参数: idCardName(姓名), idCardNumber(身份证号), frontFile(正面照), backFile(背面照)
```

### 3.6 志愿者功能

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/volunteer/profile` | 获取志愿者资料 |
| PUT | `/api/volunteer/profile` | 更新志愿者资料 |
| POST | `/api/volunteer/verification` | 上传资质证件（multipart） |
| GET | `/api/volunteer/verification/status` | 获取认证状态 |
| GET | `/api/volunteer/dispatch-summary` | **首页聚合数据**（接单资格/在线位置/覆盖范围/时段/评分/活跃订单/近期记录，详见 [`volunteer-dispatch-summary.md`](volunteer-dispatch-summary.md)） |
| PUT | `/api/volunteer/dispatch-status` | 切换接单开关 `{wantsDispatch:bool}` |
| POST | `/api/volunteer/location` | 上报位置（@Deprecated，改用 WebSocket） |

**VolunteerProfileUpdateRequest**:
```json
{
  "name": "王五",
  "availableTimeSlots": [
    {
      "dayOfWeek": "MONDAY",
      "startTime": "08:00",
      "endTime": "12:00"
    }
  ]
}
```

**VolunteerLocationRequest**:
```json
{
  "latitude": 39.9042,
  "longitude": 116.4074,
  "isOnline": true
}
```

### 3.7 订单管理

#### 订单状态流转
```
PENDING_MATCH → PENDING_ACCEPT → DRIVER_EN_ROUTE → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
       ↓              ↓                ↓                 ↓               ↓
    CANCELLED    REMATCHING        REMATCHING        REMATCHING      (仅志愿者可 /finish)
```
> **语义说明（v2，iOS 对齐）**：`PENDING_ACCEPT` = 志愿者已接单、待出发；`DRIVER_EN_ROUTE` = 志愿者已出发；`DRIVER_ARRIVED` = 志愿者已到达盲人位置；志愿者点击"开始服务"调用 `POST /{id}/start-service` 后进入 `IN_PROGRESS`（陪跑进行中）；`/finish` **仅接受 `IN_PROGRESS` 状态**（不再接受 `DRIVER_EN_ROUTE`/`DRIVER_ARRIVED` 直接结束）。
> **创建订单提前量**：`plannedStartTime` 必须 ≥ 当前时间 + 30 分钟，否则 HTTP 422 + `errorCode: APPOINTMENT_TOO_SOON`。

| 方法 | 路径 | 说明 | 角色 |
|------|------|------|------|
| POST | `/api/orders` | 创建订单 | BLIND |
| GET | `/api/orders/available` | 获取可接订单 | VOLUNTEER |
| POST | `/api/orders/{id}/respond` | 响应派单（接单/跳过）⭐推荐 | VOLUNTEER |
| POST | `/api/orders/{id}/accept` | 接单（已废弃；**B2 后复用 /respond 校验，接单异步进入 PENDING_ACCEPT**） | VOLUNTEER |
| POST | `/api/orders/{id}/en-route` | 出发 | VOLUNTEER |
| POST | `/api/orders/{id}/arrived` | 到达 | VOLUNTEER |
| POST | `/api/orders/{id}/start-service` | 确认开始服务（`DRIVER_ARRIVED → IN_PROGRESS`） | VOLUNTEER |
| POST | `/api/orders/{id}/finish` | 完成（仅 `IN_PROGRESS` 可结束） | VOLUNTEER |
| POST | `/api/orders/{id}/cancel` | 取消 | BLIND/VOLUNTEER |
| PUT | `/api/orders/{id}/keep-waiting` | 延长等待 | BLIND |
| GET | `/api/orders/{id}` | 订单详情 | 任意 |
| GET | `/api/orders/mine` | 我的订单列表 | 任意 |
| GET | `/api/orders/{id}/status-logs` | 状态变更日志 | 任意 |
| POST | `/api/orders/{id}/review` | 提交评价 | 任意 |
| GET | `/api/orders/{id}/reviews` | 获取评价 | 任意 |

**CreateOrderRequest**:
```json
{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-20T14:00:00",
  "plannedEndTime": "2026-04-20T15:00:00"
}
```

> **前置条件**: 盲人用户必须至少有 1 个紧急联系人才能创建订单

### 3.8 紧急求助

#### 紧急事件状态流转
```
PENDING → VOLUNTEER_NOTIFIED → VOLUNTEER_CONFIRMED → CONTACT_NOTIFIED → CS_HANDLING → RESOLVED
                                                                                     ↘ FALSE_ALARM
```

| 方法 | 路径 | 说明 | 角色 |
|------|------|------|------|
| POST | `/api/emergency/trigger` | 触发紧急求助 | BLIND/VOLUNTEER |
| PUT | `/api/emergency/{eventId}/volunteer-response` | 志愿者响应 | VOLUNTEER |

**EmergencyTriggerRequest**:
```json
{
  "orderId": 123,        // 可选（A1：独立 SOS 可不传；传则校验是否订单参与者）
  "gpsLat": 39.9042,     // 可选（A2：可不传，无坐标短信走"报警110"引导）
  "gpsLng": 116.4074     // 可选
}
```

> **A1/A2/S5 更新**：① 无 `orderId` 可触发独立 SOS（直接升级紧急联系人 + 推客服）；② 紧急联系人短信位置**三级降级**——逆地理编码文字地址（高德 regeo，需配 `AMAP_WEB_KEY`）→ 可读经纬度 → "报警110"引导，**短信禁链接**（违反运营商规定）；③ 用户**未设置紧急联系人**时，盲人收到"未找到联系人，已转客服"通知，事件转 `CS_HANDLING`。

**Volunteer Response**:
```json
// PUT /api/emergency/{eventId}/volunteer-response?action=NEED_HELP
// action 可选: NEED_HELP（需要帮助）, FALSE_ALARM（误报）
```

### 3.9 客服 / 管理员

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/cs/emergency-events` | 获取紧急事件列表 |
| PUT | `/api/cs/emergency-events/{id}/accept` | 接手处理 |
| PUT | `/api/cs/emergency-events/{id}/notify-contact` | 通知紧急联系人 |
| PUT | `/api/cs/emergency-events/{id}/resolve` | 标记已解决 |
| PUT | `/api/cs/emergency-events/{id}/false-alarm` | 标记误报 |
| GET | `/api/admin/volunteers/review/id` | 获取待审核身份证列表 |
| POST | `/api/admin/volunteers/review/id` | 审核身份证 |
| GET | `/api/admin/volunteers/training/stats` | 培训统计 |
| POST | `/api/admin/volunteers/training/courses` | 创建培训课程 |
| PUT | `/api/admin/volunteers/training/courses/{id}` | 更新课程 |
| DELETE | `/api/admin/volunteers/training/courses/{id}` | 删除课程 |
| GET | `/api/admin/notification-templates` | 通知模板列表 |
| PUT | `/api/admin/notification-templates/{id}` | 更新模板 |

### 3.10 通话功能

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders/{orderId}/call/initiate` | 发起虚拟号码通话 |
| GET | `/api/orders/{orderId}/call/records` | 获取通话记录 |

**CallInitiateRequest**:
```json
{
  "callerRole": "BLIND_USER"   // 枚举: BLIND_USER 或 VOLUNTEER
}
```

---

## 四、WebSocket 实时通信

### 4.1 连接

```javascript
const token = localStorage.getItem('token');
const ws = new WebSocket(`ws://47.114.113.171/ws/volunteer?token=${token}`);

ws.onopen = () => console.log('WebSocket 已连接');

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  console.log('收到消息:', msg.type, msg);
};

// 自动重连
ws.onclose = () => {
  setTimeout(() => {
    // 重新连接...
  }, 3000);
};
```

### 4.2 服务端推送的消息类型

#### 新订单通知
```json
{
  "type": "NEW_ORDER",
  "data": {
    "orderId": 123,
    "blindName": "李明",
    "startAddress": "朝阳公园南门",
    "plannedStartTime": "2026-04-20T14:00:00",
    "plannedEndTime": "2026-04-20T15:00:00"
  }
}
```

#### 通用通知
```json
{
  "type": "NOTIFICATION",
  "data": {
    "eventType": "ORDER_ACCEPTED",
    "message": "志愿者张三已接单",
    "ttsText": "志愿者张三已接单，请注意接听",
    "priority": "HIGH"
  }
}
```

#### 紧急求助警报
```json
{
  "type": "EMERGENCY",
  "data": {
    "eventId": 456,
    "orderId": 123,
    "message": "用户触发紧急求助",
    "gpsLat": 39.9042,
    "gpsLng": 116.4074
  }
}
```

#### 心跳
```json
// 客户端发送
{ "type": "PING" }
// 服务端回复
{ "type": "PONG" }
```

### 4.3 客户端发送的消息

#### 志愿者位置上报
```json
{
  "type": "LOCATION_UPDATE",
  "lat": 39.9042,
  "lng": 116.4074
}
```

### 4.4 盲人用户 WebSocket

盲人用户连接 `/ws/blind` 端点，用于：
- 上报自己的实时位置（与 REST `POST /api/blind/location` 效果相同）
- **实时接收志愿者位置更新**（订单状态为出发/到达时自动推送）

```javascript
const token = localStorage.getItem('token');
const blindWs = new WebSocket(`ws://47.114.113.171/ws/blind?token=${token}`);

blindWs.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'VOLUNTEER_LOCATION_UPDATE') {
    // 志愿者实时位置（类似滴滴打车地图追踪）
    console.log('志愿者位置:', msg.lat, msg.lng);
    // 在地图上更新志愿者标记
  }
};

// 上报盲人位置
blindWs.send(JSON.stringify({
  type: "LOCATION_UPDATE",
  lat: 31.23,
  lng: 121.47
}));

// 心跳
blindWs.send(JSON.stringify({ type: "PING" }));
```

#### 接收志愿者位置更新
```json
{
  "type": "VOLUNTEER_LOCATION_UPDATE",
  "orderId": 123,
  "lat": 39.9042,
  "lng": 116.4074,
  "timestamp": 1713628800000
}
```

> **推送时机**: 订单状态为 `DRIVER_EN_ROUTE`（志愿者出发）或 `DRIVER_ARRIVED`（志愿者到达）时，每次志愿者上报位置都会自动推送给对应盲人用户。

### 4.5 REST 降级查询

当 WebSocket 不可用时，盲人可通过 REST 接口轮询志愿者位置：

```
GET /api/blind/volunteer-location
Authorization: Bearer <token>
```

**成功响应**:
```json
{
  "success": true,
  "code": 200,
  "data": {
    "lat": 39.9042,
    "lng": 116.4074,
    "orderId": 123,
    "orderStatus": "DRIVER_EN_ROUTE"
  }
}
```

**无进行中订单**: HTTP 404
```json
{ "success": false, "code": 404, "message": "没有进行中的订单" }
```

---

## 五、错误处理

### HTTP 状态码

| 状态码 | 含义 | 示例 |
|--------|------|------|
| 200 | 成功 | — |
| 400 | 参数错误 | 手机号格式不正确 |
| 401 | 未认证 | token 无效或过期 |
| 403 | 无权限 | 非盲人用户尝试创建订单 |
| 404 | 资源不存在 | 订单不存在 |
| 409 | 业务冲突 | 重复下单、状态不允许 |
| 429 | 请求过于频繁 | 触发限流 |

### 错误响应格式

```json
// 方式1（部分接口）
{ "error": "用户名或密码错误" }

// 方式2（部分接口）
{ "success": false, "code": 400, "message": "参数验证失败" }

// 方式3（限流）
{ "error": "请求过于频繁", "message": "请稍后再试", "retryAfterSeconds": 58 }
```

---

## 六、功能实现状态

### 已实现（可正常使用）

| 功能 | 说明 |
|------|------|
| 短信验证码登录 | 阿里云短信，真正发送 |
| 用户角色管理 | 设置 BLIND/VOLUNTEER 角色 |
| 盲人资料管理 | 增删改查 |
| 紧急联系人管理 | 1~5 个，本人读取返回**明文**电话（v1.5.0，前端展示层自行脱敏） |
| 志愿者 4 步注册 | 基本信息→身份证→人脸→培训 |
| 志愿者资料管理 | 含时间段设置 |
| 订单完整生命周期 | 创建→匹配→接单→出发→到达→完成 |
| 订单取消 & 重新匹配 | 支持双方取消 |
| WebSocket 实时推送 | 订单通知、紧急警报、通用通知 |
| 志愿者位置上报 | REST + WebSocket 双通道 |
| 紧急求助触发 | 盲人/志愿者触发 |
| 志愿者紧急响应 | 30 秒超时 |
| 客服紧急事件处理 | 接手、通知联系人、解决、标记误报 |
| 管理员审核身份证 | 通过/拒绝 |
| 培训课程管理 | CRUD |
| 通知模板管理 | 模板 + 占位符替换 |
| 限流保护 | auth 10/min, 注册 20/min, 通用 60/min |
| 客服登录锁定 | 5 次失败锁定 15 分钟 |

### 第三方服务接入状态

| 功能 | 当前状态 | 说明 |
|------|---------|------|
| 紧急联系人短信通知 | ✅ 已接入 | 阿里云 Dysmsapi（`EMERGENCY_ALERT` 等模板），非模拟 |
| 人脸验证（志愿者 Step 3） | ✅ 已接入 | 阿里云金融级实人认证 `ContrastFaceVerify`（`ID_MIN`），非 stub |
| 隐私号码通话 | ⚠️ 默认 mock | `aliyun.private-number.enabled=false` 时返回假号码 170xxx；开通阿里云隐私号服务并设 `enabled=true` 可接真实 |

### 未实现

| 功能 | 说明 |
|------|------|
| AI 语音外呼 | 框架预留（`AI_VOICE_CALL` 枚举），未实现 |
| App 推送 | 枚举定义存在，未接入推送服务 |
| AI 自动检测紧急 | 预留字段，未实现 |

---

## 七、前端开发注意事项

### 7.1 跨域（CORS）

已允许的来源：
- `http://localhost:3000`
- `http://localhost:5173`
- `http://127.0.0.1:3000`
- `http://127.0.0.1:5173`
- `http://47.114.113.171`

如果你的开发端口不在列表里，联系后端添加。

### 7.2 Token 使用

```
// 登录后保存 token
localStorage.setItem('token', response.token);

// 每次 API 请求带上
fetch('http://47.114.113.171/api/orders/mine', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  }
});

// WebSocket 连接也带上
const ws = new WebSocket(`ws://47.114.113.171/ws/volunteer?token=${token}`);
```

Token 有效期 24 小时，过期后需要重新登录。

### 7.3 手机号脱敏

所有 API 返回的手机号都是脱敏格式（`138****0001`），前端无法获取完整手机号。

### 7.4 时间格式

所有时间字段使用 ISO 8601 格式：`2026-04-20T14:00:00`

### 7.5 限流注意

| 接口 | 限制 |
|------|------|
| `/api/auth/*` | 10 次/分钟 |
| `/api/volunteer/registration/*` | 20 次/分钟 |
| 其他 `/api/*` | 60 次/分钟 |

超限返回 HTTP 429，响应头包含 `Retry-After`。

### 7.6 Swagger 调试

1. 打开 `http://47.114.113.171/swagger-ui/index.html`
2. 先调用 `/api/auth/verify-code` 获取 token
3. 点击右上角 **Authorize**，输入 `Bearer <token>`
4. 然后就能在 Swagger 里直接测试所有接口

### 7.7 文件上传

身份证和资质证件上传使用 `multipart/form-data`：
```javascript
const formData = new FormData();
formData.append('frontFile', frontFile);
formData.append('backFile', backFile);
formData.append('idCardName', '张三');
formData.append('idCardNumber', '110101199001011234');

fetch('http://47.114.113.171/api/volunteer/registration/step2/id-card', {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${token}` },
  body: formData  // 注意：不要手动设置 Content-Type
});
```

文件限制：仅支持 `.jpg/.jpeg/.png/.gif/.webp/.bmp/.pdf`，最大 5MB。

---

## 八、常见问题

**Q: 登录收不到验证码？**
A: 检查手机号格式（11 位数字），60 秒内不能重复发送。

**Q: 创建订单返回"请先添加紧急联系人"？**
A: 盲人用户必须先添加至少 1 个紧急联系人才能下单。

**Q: 志愿者接单返回"请先完成志愿者注册流程"？**
A: 志愿者必须完成全部 4 步注册（基本信息→身份证→人脸→培训）才能接单。

**Q: WebSocket 连接失败？**
A: 检查 token 是否有效，URL 是否正确（`ws://` 不是 `http://`）。

**Q: 通话功能返回的号码是假的？**
A: 通话功能目前是模拟实现，返回 170 开头的假号码，真实号码需要开通阿里云隐私号码服务。

**Q: 后端启动报数据库/Redis连接失败？**
```bash
# 检查MySQL是否运行
mysql -u root -p -e "SELECT 1;"

# 检查Redis是否运行
redis-cli ping   # 应返回 PONG

# 检查端口是否占用
lsof -i :8081
```

**Q: 前端请求被CORS阻止？**
A: 后端已配置 CORS，允许 `localhost:3000` 和 `localhost:5173`。确保前端开发服务器运行在这两个端口之一，macOS 上避免用 `http://localhost`（改用 `http://127.0.0.1`）。

**Q: 返回401，确认token正确但还是报错？**
A: 注意 `Authorization` 头格式：`Bearer ` 后面有一个空格。JWT 默认24小时过期，过期后需重新登录。

**Q: 志愿者收到NEW_ORDER消息后如何响应？**
A: 调用 `POST /api/orders/{id}/respond` 传 `{"action":"ACCEPT"}` 接单或 `{"action":"DECLINE"}` 跳过。有30秒时间窗口，超时自动推给下一个志愿者。

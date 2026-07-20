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
> | `ID_INFO_INVALID` | 400 | 身份证信息核验未通过（Step1 二要素不一致，或 Step3 init 发现身份证异常），前端应引导用户修改身份证姓名/号码 |
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

> **坐标系约定（2026-07-19 确认）**：全部接口的 `lat`/`lng`（含 `gpsLat`/`gpsLng`）统一使用 **GCJ-02**（国测局加密坐标，与高德/腾讯定位 SDK 输出一致）。前端上报定位时**不要**使用系统原生 GPS（`CLLocationManager`/原生 `LocationManager`）的 WGS-84 原始值，务必用高德/腾讯定位 SDK 输出的坐标，否则地图展示、走散检测、逆地理编码地址会产生偏移。

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

### 2.3 登出与账号注销

```json
// POST /api/auth/logout（或客服 POST /api/cs/auth/logout）
// 请求头：Authorization: Bearer <token>
// 响应
{ "success": true }
```

**⚠️ 登出契约（S11，2026-07-13 确认）**：登出只撤销**本次请求携带的这一个** token，不影响同账号其他仍在有效期内的 token。
典型场景：登录拿 Token A → `POST /api/user/role` 选角色拿到替换 Token B → 前端务必用**最新（Token B）**去调用后续接口和登出；若仍用旧的 Token A 调登出，Token A 会失效但 Token B 不受影响（这正是设计意图，不是 bug）。

```json
// DELETE /api/users/{id}（注销账号，只能删自己）
// 响应（成功）
{ "success": true, "phoneReusable": true, "allTokensInvalidated": true }

// 响应（删除他人 ID，HTTP 403）

// 响应（有进行中订单，HTTP 409）
{ "success": false, "code": 409, "errorCode": "ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED", "message": "您有进行中的订单，无法注销" }
```

- 注销**撤销该账户全部 token**（与登出的单 token 撤销不同，因为账号已不存在，没有"保留其他会话"的意义）。
- 注销成功后原手机号立即释放，可重新注册。

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

### 3.5 志愿者注册（3步流程）

注册流程：`BASIC_INFO（含身份证二要素核验）→ FACE_VERIFY（动作活体）→ TRAINING → COMPLETED`

> step2 身份证正反面照片上传已下线，身份证姓名+号码并入 step1 提交，由后端自动调用阿里云 Id2Meta 二要素核验；人脸验证升级为阿里云动作活体（SMART 方案，App SDK 集成），客户端用阿里云原生 App SDK（`AliyunFaceAuthFacade.verify(certifyId)`）直接完成眨眼/点头交互（无需打开 URL）后轮询结果。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/volunteer/registration/status` | 获取注册进度 |
| POST | `/api/volunteer/registration/step1` | 提交基本信息 + 身份证姓名/号码（自动二要素核验） |
| POST | `/api/volunteer/registration/step3/face-verify/init` | 发起动作活体，返回 `certifyId` |
| POST | `/api/volunteer/registration/step3/face-verify/result` | 查询动作活体结果（轮询） |
| GET | `/api/volunteer/registration/training/courses` | 获取培训课程列表 |
| POST | `/api/volunteer/registration/training/progress` | 提交学习进度 |
| GET | `/api/volunteer/registration/training/quiz/{courseId}` | 获取测验题目 |
| POST | `/api/volunteer/registration/training/quiz/answer` | 提交测验答案 |

**Step1 BasicInfoRequest**（提交后后端自动做 Id2Meta 二要素核验。**通过** → 推进到 FACE_VERIFY；**未通过** → 保持 `STEP_1_BASIC_INFO` + 返回 HTTP 400 `errorCode: ID_INFO_INVALID`，前端停留在 Step1 引导用户修改身份证信息。后端会自动 trim + 末尾 X 大写归一化，前端无需额外处理空格/大小写）:
```json
{
  "name": "王五",
  "phone": "13800138000",
  "idCardName": "王五",
  "idCardNumber": "110101199001011234",
  "runningExperience": "3年跑步经验",
  "hasGuidedBefore": true,
  "emergencyExperience": "有急救证书"
}
```

**Step3 动作活体（两段式，阿里云 SMART 方案）**：

1) `POST /api/volunteer/registration/step3/face-verify/init`，请求体 `FaceVerifyInitRequest`：
```json
{
  "metaInfo": "<前端用阿里云 JS SDK 采集的设备指纹 JSON 字符串>"
}
```
响应 `FaceVerifyInitResponse`：
```json
{
  "certifyId": "xxxx-xxxx-xxxx",
  "certifyUrl": null,                           // 恒为 null，App SDK 场景阿里云不返回该字段，客户端无需处理
  "status": "PENDING",                          // PENDING=已发起，客户端用 App SDK 完成动作活体 / ERROR=发起失败
  "message": "ok"
}
```
客户端拿到 `certifyId` 后，使用阿里云原生 App SDK（iOS `AliyunFaceAuthFacade.verify(certifyId)`）直接发起眨眼/点头动作活体，无需打开任何 URL。
> ⚠️ **身份证异常回退**：若 init 返回 HTTP 400 `errorCode: ID_INFO_INVALID`（历史脏数据：Step1 二要素未通过却已推进到 step3），后端已自动将步骤回退到 `STEP_1_BASIC_INFO`。前端收到后调 `GET /status` 刷新，引导用户回 Step1 修改身份证信息。

2) `POST /api/volunteer/registration/step3/face-verify/result`，请求体 `FaceVerifyResultRequest`：
```json
{
  "certifyId": "xxxx-xxxx-xxxx"
}
```
响应 `FaceVerifyResultResponse`：
```json
{
  "passed": true,
  "status": "APPROVED",  // APPROVED=通过，进入培训 / REJECTED=失败，可重新 init / PENDING=进行中，继续轮询
  "message": "ok"
}
```
> 前端轮询建议：每 2~3 秒调用一次 result，直到 `status` 落到 APPROVED/REJECTED；PENDING 继续轮询。`certifyId` 必须与当前用户绑定的 init 返回一致。

**培训学习模块（4 个接口，均在 STEP_4_TRAINING / STEP_4_COMPLETED 下可用）**：

- **内容格式**：`GET /training/courses` 返回的 `content` 是 **HTML 富文本**（非 Markdown），前端用 WebView / `NSAttributedString(html:)` 渲染；`videoUrl` 为空时渲染 `content`，非空时优先播放视频。当前 3 门种子课程 `videoUrl` 均为 `null`。
- **进度由客户端计算**：`POST /training/progress` 的 `progressPercent` 由 iOS 自行算好后直接提交，后端**不会**根据 `lastPositionSeconds`/`timeSpentSeconds` 反算，这两个字段仅存档 + 反作弊用。反作弊规则：进度不能倒退（提交值 ≤ 当前值且 <100 会报 400，`errorCode=TRAINING_PROGRESS_REGRESSION`，但提交 100 允许重复）；提交速率不能超过约每分钟 100%（正常观看速度的 10 倍），否则 400，`errorCode=TRAINING_PROGRESS_RATE_ANOMALY`。**接口非严格幂等**——同一非 100 的百分比重复提交会报错，不要做"失败自动重试同值"的逻辑，重试前应先 `GET /courses` 核对当前值。并发写同一课程进度（如断网重发）时可能命中乐观锁/唯一约束冲突，也是 400，`errorCode=TRAINING_PROGRESS_CONFLICT`——前端遇到这个码直接重试一次即可，不需要特殊提示。
- **限流**：`training/*` 与 `step1`/`step3/*` 共享同一个 **20 次/分钟/IP** 的 `registration` 限流桶（不是按用户、不是按课程单独限流）。触发返回 **HTTP 429**，body `{error:"TOO_MANY_REQUESTS", message, retryAfterSeconds}`（注意字段名是 `error` 不是 `errorCode`），同时带 `Retry-After` 响应头。
- **测验解锁阈值：进度 ≥95%**（不是 100%）。`GET /training/courses` 返回的 `TrainingCourseResponse` 现已带 `canTakeQuiz`（布尔，等价于 `progressPercent>=95`）和 `quizLockedReason`（未解锁时的中文提示，已解锁为 `null`），前端可直接用 `canTakeQuiz` 控制测验入口显隐，不必自己算阈值。`GET /training/quiz/{courseId}` 和 `POST /training/quiz/answer` 在未达标时仍返回 **HTTP 400**，body 带稳定 `errorCode=TRAINING_QUIZ_LOCKED`（课程完全没学过是 `TRAINING_NOT_STARTED`），前端可据此程序化判断，不必匹配中文文案。
- **答案格式**：`options`/`answers` 传输的是**完整选项文本原文**，不是字母码 "A"/"B"（代码注释已同步更正，与实际行为一致）。单选题 `answers` 是长度 1 的字符串数组，多选题是长度 >1 的数组，元素必须**逐字精确等于** `GET /training/quiz/{courseId}` 返回的某个 `options[i]`（顺序每次请求随机打乱，多选不区分提交顺序）。
- **逐题提交**：`POST /training/quiz/answer` 一次只提交一题；每次提交后立即按"每题最新一次作答"重新汇总整卷结果返回（`correctCount/totalQuestions/scorePercent/passed`），不存在"交卷"这个动作。
- **及格线 60%，不限答题次数**：`QuizResultResponse.remainingAttempts` 恒为 `-1`（哨兵值，代表无限次），不是真实剩余次数计数，无冷却时间、无重置逻辑。
- **完成判定（确认为既定设计，非缺陷）**：志愿者所有必修课程 `progressPercent` 均达到 **100%**（`progressStatus=COMPLETED`）后，后端自动将 `registrationStep` 置为 `STEP_4_COMPLETED` 并 `verified=true`——**该判定不校验测验是否通过**，只要每门课都完整学过一遍即可转正，测验分数不影响接单资格，这是产品侧确认过的行为，前端无需额外拦截。
- **errorCode 速查**（均为 HTTP 400，仅 `errorCode` 不同，前端可按需分支）：`TRAINING_COURSE_NOT_FOUND` 课程不存在 / `TRAINING_STEP_NOT_REACHED` 注册步骤未到培训阶段 / `TRAINING_PREREQUISITE_NOT_MET` 前置课程未完成 / `TRAINING_PROGRESS_REGRESSION` 进度倒退 / `TRAINING_PROGRESS_RATE_ANOMALY` 提交速率异常 / `TRAINING_PROGRESS_CONFLICT` 并发冲突需重试 / `TRAINING_NOT_STARTED` 尚未开始学习该课程 / `TRAINING_QUIZ_LOCKED` 测验未解锁 / `TRAINING_QUESTION_NOT_FOUND` 题目不存在 / `TRAINING_QUESTION_COURSE_MISMATCH` 题目不属于该课程 / `TRAINING_ERROR` 兜底通用错误。
- **响应 schema**：完整字段定义已同步到 `docs/api_spec.yaml`（`TrainingCourseResponse` / `QuizQuestionResponse` / `QuizResultResponse` / `QuestionResult`），`/v3/api-docs` 也已从泛化 `object` 改为具体 schema。

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
| GET | `/api/orders/{id}/track` | 陪跑轨迹回放（历史路径 + 统计） | 任意 |
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

#### 陪跑轨迹回放

订单结束后，双方可查看本次陪跑的完整路径回放（类似健身 App 的轨迹地图）。鉴权与 `GET /api/orders/{id}` 一致（仅订单双方本人可访问，否则 403）。

```
GET /api/orders/{id}/track
Authorization: Bearer <token>
```

**响应（`OrderTrackResponse`）**:
```json
{
  "status": "COMPLETED",
  "volunteerTrack": [
    { "lat": 39.9042, "lng": 116.4074, "recordedAt": "2026-04-20T14:05:00" },
    { "lat": 39.9045, "lng": 116.4078, "recordedAt": "2026-04-20T14:05:10" }
  ],
  "volunteerStats": { "distanceMeters": 850.5, "durationSeconds": 620, "avgPaceSecPerKm": 365.2 },
  "blindTrack": [
    { "lat": 39.9042, "lng": 116.4074, "recordedAt": "2026-04-20T14:05:00" }
  ],
  "blindStats": { "distanceMeters": 830.1, "durationSeconds": 620, "avgPaceSecPerKm": 372.0 }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | 订单当前状态（`OrderStatus` 枚举），用于区分空轨迹的含义（见下方说明） |
| volunteerTrack / blindTrack | array | 各自的轨迹点列表，按时间升序；覆盖整个订单 `IN_PROGRESS` 阶段，约每 10 秒抽稀一个点（并非每次原始 GPS 上报都入库） |
| volunteerTrack[].lat / lng | number | 纬度 / 经度 |
| volunteerTrack[].recordedAt | string | 记录时间（ISO 格式） |
| volunteerStats / blindStats | object | 各自的统计数据 |
| xxxStats.distanceMeters | number | 总里程（米） |
| xxxStats.durationSeconds | number | 耗时（秒） |
| xxxStats.avgPaceSecPerKm | number \| null | 平均配速（秒/公里）；该角色轨迹点少于 2 个时为 `null`（对应统计字段也全为 0） |

> **抽稀间隔可配**: `app.track.sample-interval-seconds`（默认 10 秒）

> **如何解读空/短轨迹**（结合 `status` 判断）：
> - `status` 为 `IN_PROGRESS` 之前的状态（`PENDING_MATCH`/`PENDING_ACCEPT`/`DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`）且轨迹为空 —— 陪跑尚未开始，正常现象；
> - `status` 为 `IN_PROGRESS` 且轨迹为空或很短 —— 陪跑刚开始，数据还在采集中；
> - `status` 为终态（`COMPLETED`/`CANCELLED`/`REMATCHING`/`NO_VOLUNTEER` 等）但轨迹为空 —— 该订单是轨迹功能上线前的历史订单，不支持轨迹回放。

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
| GET | `/api/cs/emergency-events?status={status}` | 获取紧急事件列表（`status` 可选，为空时返回未结束事件；传值需为 `EmergencyStatus` 枚举，用于筛选查看历史）。响应含 `gpsLat`/`gpsLng`（**仅 CS_ADMIN 非 null**，CS_CS 恒为 null）+ `hasGpsLocation` 布尔值（两角色皆可用，判断是否有坐标） |
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

#### 紧急求助警报（推送给客服，`sendToCs`）

> ⚠️ 当前 `UnifiedSessionRegistry.sendToCs()` 未挂载实际的 `/ws/cs` 端点（无客服 WebSocket 连接入口），此消息目前不会被任何客户端收到。客服/管理员应通过轮询 `GET /api/cs/emergency-events` 获取事件列表（含状态过滤），管理员额外可见 `gpsLat`/`gpsLng` 原始坐标（见 3.9 节）。此处保留消息格式供未来接入 CS WebSocket 时参考。

```json
{
  "type": "EMERGENCY_ALERT",
  "eventId": 456,
  "userId": 1,
  "orderId": 123,
  "hasGpsLocation": true,
  "priority": "HIGH",
  "triggeredAt": "2026-07-19T14:30:00"
}
```

> **2026-07-19 起**：不再包含原始 `gpsLat`/`gpsLng`（S13 修复，避免坐标广播给全体在线客服）；改为 `hasGpsLocation` 布尔值。原始坐标仅 CS_ADMIN 通过 `GET /api/cs/emergency-events` 按需查看。

#### 接收盲人位置更新（志愿者端）
```json
{
  "type": "BLIND_LOCATION_UPDATE",
  "orderId": 123,
  "lat": 39.9050,
  "lng": 116.4080,
  "timestamp": 1716480000000
}
```
> **推送时机**: 订单状态为 `DRIVER_EN_ROUTE`（志愿者出发）、`DRIVER_ARRIVED`（志愿者到达）或 `IN_PROGRESS`（陪跑进行中）时，每次盲人上报位置都会自动推送给对应志愿者；与盲人端接收的 `VOLUNTEER_LOCATION_UPDATE`（见 4.4）方向相反、格式相同，`IN_PROGRESS` 阶段用于陪跑中双方实时同步位置（详见 4.4 中的对称说明）。

#### 走散告警
`IN_PROGRESS`（陪跑中）阶段，若双方实时 GPS 距离超过阈值（默认 100 米，`app.escort.max-distance-meters` 可配），双方各自收到一条走散提醒（`eventType: ESCORT_DISTANCE_ALERT`，仍走上方"通用通知"消息格式，`type` 字段为 `NOTIFICATION`/`APP_NOTIFICATION`）：
```json
{
  "type": "NOTIFICATION",
  "data": {
    "eventType": "ESCORT_DISTANCE_ALERT",
    "message": "与盲人用户的距离似乎有点远",
    "ttsText": "你和盲人用户的距离似乎有点远，请尽快确认对方位置",
    "priority": "HIGH"
  }
}
```
> 盲人端收到的文案对应为「与志愿者的距离似乎有点远」/「你和志愿者的距离似乎有点远，请留在原地，志愿者正在确认位置」。同一次距离越界会**并行**触发既有紧急升级流程（`triggerType: AI_DETECTED`）通知客服介入，二者互不替代：走散告警是双端的即时轻量提醒，紧急升级是走向客服的分级处理。

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
- **实时接收志愿者位置更新**（订单状态为出发/到达/陪跑进行中时自动推送）

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

> **推送时机**: 订单状态为 `DRIVER_EN_ROUTE`（志愿者出发）、`DRIVER_ARRIVED`（志愿者到达）或 `IN_PROGRESS`（陪跑进行中）时，每次志愿者上报位置都会自动推送给对应盲人用户；`IN_PROGRESS` 阶段此前存在推送空档（只推到 `DRIVER_ARRIVED`），现已补全，用于陪跑中双方实时同步位置。志愿者端对称地通过 `BLIND_LOCATION_UPDATE` 接收盲人位置（见 4.2）。

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
| 志愿者 3 步注册 | 基本信息+身份证二要素→动作活体→培训 |
| 志愿者资料管理 | 含时间段设置 |
| 订单完整生命周期 | 创建→匹配→接单→出发→到达→完成 |
| 订单取消 & 重新匹配 | 支持双方取消 |
| WebSocket 实时推送 | 订单通知、紧急警报、通用通知 |
| 志愿者位置上报 | REST + WebSocket 双通道 |
| 陪跑实时位置互推 | `IN_PROGRESS` 阶段双方通过 WS 互相接收对方位置（`VOLUNTEER_LOCATION_UPDATE` / `BLIND_LOCATION_UPDATE`） |
| 陪跑轨迹回放 | `GET /api/orders/{id}/track`，历史路径 + 里程/耗时/配速统计 |
| 走散检测告警 | `IN_PROGRESS` 阶段双方距离超阈值（默认 100 米）时 WS 实时提醒双方 |
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
| 动作活体验证（志愿者 Step 3） | ✅ 已接入 | 阿里云金融级实人认证 `InitFaceVerify` + `DescribeFaceVerify`（SMART 方案，眨眼/点头），非 stub |
| 隐私号码通话 | ⚠️ 默认 mock | `aliyun.private-number.enabled=false` 时返回假号码 170xxx；开通阿里云隐私号服务并设 `enabled=true` 可接真实 |

### 未实现

| 功能 | 说明 |
|------|------|
| AI 语音外呼 | 框架预留（`AI_VOICE_CALL` 枚举），未实现 |
| App 推送 | 枚举定义存在，未接入推送服务 |
| AI 自动检测紧急 | 部分实现：陪跑走散检测（GPS 距离超阈值）会以 `triggerType: AI_DETECTED` 触发紧急升级，通用 AI 异常检测仍未实现 |

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

### 7.7 文件上传 & 动作活体

身份证正反面照片上传已下线（step2 删除）。身份证信息走 step1 表单字段（`idCardName`/`idCardNumber`），由后端做二要素核验。

资质证件上传仍使用 `multipart/form-data`：
```javascript
const formData = new FormData();
formData.append('file', licenseFile);

fetch('http://47.114.113.171/api/volunteer/verification', {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${token}` },
  body: formData  // 注意：不要手动设置 Content-Type
});
```

文件限制：仅支持 `.jpg/.jpeg/.png/.gif/.webp/.bmp/.pdf`，最大 5MB。

动作活体（step3）为 JSON 请求，非文件上传：先 `POST .../face-verify/init` 拿到 `certifyId`，客户端用阿里云 App SDK（`AliyunFaceAuthFacade.verify(certifyId)`）直接完成交互，再轮询 `POST .../face-verify/result`。

---

## 八、常见问题

**Q: 登录收不到验证码？**
A: 检查手机号格式（11 位数字），60 秒内不能重复发送。

**Q: 创建订单返回"请先添加紧急联系人"？**
A: 盲人用户必须先添加至少 1 个紧急联系人才能下单。

**Q: 志愿者接单返回"请先完成志愿者注册流程"？**
A: 志愿者必须完成全部 3 步注册（基本信息+身份证二要素→动作活体→培训）才能接单。

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

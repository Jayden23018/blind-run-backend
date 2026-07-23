# 变更日志

## [1.5.3] - 2026-07-23

### 缺陷修复 — 订单状态变更 WebSocket 推送

- **补齐 `ORDER_STATUS_CHANGED` 双端推送**：此前 `docs/websocket-protocol.md` 约定了该消息但后端从未实现——订单状态推进（志愿者出发 `DRIVER_EN_ROUTE` / 到达 `DRIVER_ARRIVED` / 开始服务 `IN_PROGRESS` / 完成 `COMPLETED`）时，盲人和志愿者 WebSocket 都收不到结构化状态变更事件（前端只能靠轮询 HTTP）。
- **修复后**：每次状态推进，盲人与志愿者**各自收到一条** `ORDER_STATUS_CHANGED`：
  ```json
  {"type":"ORDER_STATUS_CHANGED","messageId":"...","timestamp":"...","orderId":123,"fromStatus":"PENDING_ACCEPT","toStatus":"DRIVER_EN_ROUTE","message":"志愿者已出发","ttsText":"志愿者已出发，正在赶往您的位置","priority":"NORMAL"}
  ```
- ⚠️ **前端对齐**：前端应按 `msg.type === "ORDER_STATUS_CHANGED"` 监听并驱动订单状态机；消息内的 `messageId`（UUID）可用于断线重连去重。现有 `APP_NOTIFICATION` 模板通知（含 TTS 语音播报）保持不变，不冲突。
- **部署**：无数据库迁移，重启即生效。

## [1.5.2] - 2026-07-19

### 缺陷修复 — 紧急事件 GPS/PII 安全整改

- **紧急事件 WS 广播不再泄露原始坐标**：`sendEmergencyAlert()` 推给全体在线客服的 WebSocket 消息，原始 `gpsLat`/`gpsLng` 改为 `hasGpsLocation` 布尔值，与 REST 接口脱敏口径一致（志愿者端 `sendEmergencyVolunteerAlert()` 不受影响，志愿者仍能看到坐标以便定位盲人）。
- **盲人 WebSocket 断开新增位置清理**：`blind:loc:{userId}` Redis key 断线时立即清理，与志愿者侧行为对称（此前只能等 30 秒 TTL 自然过期）。
- **志愿者端盲人姓名脱敏**：`GET /api/volunteer/dispatch-summary` 返回的 `blindName` 字段改为脱敏（保留首字符，如"张*"），与已脱敏的手机号字段口径一致。

### 新增 — CS_ADMIN 原始坐标查看权限 + GPS 留存清理

- **`GET /api/cs/emergency-events` 响应新增 `gpsLat`/`gpsLng` 字段**：仅当客服 JWT 的 `csRole` 为 `ADMIN` 时返回原始坐标；普通客服（`CS`）仍只能看到 `hasGpsLocation` 布尔值。
- **`GET /api/cs/emergency-events` 新增可选 `status` 查询参数**：按状态筛选事件（如查看已处理历史），不传时保持原有"待处理事件列表"行为。
- **紧急事件原始 GPS 坐标新增 90 天留存清理**：超期后坐标被清空（事件行本身保留，用于纠纷复核审计），与轨迹数据留存策略（`docs/轨迹数据留存策略.md`）口径一致。新增配置 `app.emergency.gps-retention-days`（默认 90）。

### ⚠️ 升级注意（前端）

- 志愿者端 `dispatch-summary` 接口 `blindName` 字段格式变化：从完整姓名变为脱敏格式（如"张*"），前端展示逻辑无需改动（字符串直接展示即可），但不要再依赖该字段做姓名相关的业务判断。
- 客服端 `GET /api/cs/emergency-events` 响应新增 `gpsLat`/`gpsLng` 字段（`BigDecimal`，可能为 `null`）：仅管理员账号登录时非空，普通客服账号该字段恒为 `null`，请勿假设非空。
- 客服端 WebSocket `EMERGENCY_ALERT` 消息不再含 `gpsLat`/`gpsLng`，改为 `hasGpsLocation` 布尔值；如前端曾依赖此推送展示坐标，需改为轮询 REST 接口获取（管理员账号可见原始坐标）。

### 新增 — 坐标系约定确认（GCJ-02）

- 与前端确认：全系统 `lat`/`lng`（含 `gpsLat`/`gpsLng`）统一为 **GCJ-02**（高德/腾讯定位 SDK 输出口径），非系统原生 GPS 的 WGS-84 值。
- `AmapGeocodingService` 调高德逆地理编码时显式声明 `coordsys=autonavi`（此前依赖高德隐式默认值，行为未变，仅代码层面明确意图，无需前端改动）。
- 文档同步：`CLAUDE.md`、`docs/api_spec.yaml`（`info.description`）、`docs/frontend-guide.md`（一、基础信息）、`docs/websocket-protocol.md`（一、连接）均已补充该约定说明。

---

## [1.5.1] - 2026-07-18

### 缺陷修复 — 账号注销流程加固

- **活跃订单校验补全**：`DELETE /api/users/{id}` 此前仅检查盲人侧活跃订单状态，且遗漏 `DRIVER_EN_ROUTE`/`DRIVER_ARRIVED` 两个状态；现在盲人侧完整覆盖 `PENDING_MATCH/PENDING_ACCEPT/IN_PROGRESS/DRIVER_EN_ROUTE/DRIVER_ARRIVED/REMATCHING`，且新增志愿者侧校验（`PENDING_ACCEPT/IN_PROGRESS/DRIVER_EN_ROUTE/DRIVER_ARRIVED`）。命中活跃订单时返回 HTTP **409**。
- **新增 PII 级联清理**：注销通过校验后，同步清空 `User.name`、混淆 `phone`，按角色删除 `BlindProfile`/`EmergencyContact` 或 `VolunteerProfile`/`VolunteerAvailableTime`（志愿者证件照片经 OSS/本地存储一并删除，删除失败仅记日志不阻断），两种角色统一删除陪跑轨迹点 `RunOrderTrackPoint`。`RunOrder`/`OrderReview`/`EmergencyEvent` 等两方记录保留不删（不存 PII，仅存 `userId`，用于纠纷复核审计）。

### ⚠️ 升级注意（前端）

`DELETE /api/users/{id}` 接口本身无新增，请求/响应格式不变，但行为发生变化：
- 志愿者在陪跑中 / 待出发 / 已被接单时调用该接口现在会收到 **409**（此前会被错误地允许注销）。
- 盲人在志愿者已出发 / 已到达时调用也会收到 **409**（此前的遗漏漏洞，现已补全）。

前端需确认注销按钮的错误提示能正确展示 409 场景下的"您有进行中的订单，无法注销"提示。

---

## [Unreleased]

### 新增

- **🔥 陪跑实时位置互推 + 走散告警 + 轨迹回放**：
  - **新增 `GET /api/orders/{id}/track`**：订单结束后双方可查看本次陪跑的完整路径回放（类似健身 App 轨迹地图）。鉴权与 `GET /api/orders/{id}` 一致（仅订单双方本人可访问，否则 403）。返回 `volunteerTrack`/`blindTrack`（各自轨迹点数组，约 10 秒一个点）+ `volunteerStats`/`blindStats`（里程/耗时/配速，详见 `docs/frontend-guide.md` 3.7 节）。
  - **`IN_PROGRESS`（陪跑中）阶段补全双向实时位置推送**：此前只推到 `DRIVER_ARRIVED`，陪跑真正开始后完全没有位置更新，现已修复。志愿者端通过已有的 `VOLUNTEER_LOCATION_UPDATE`（盲人 WS）接收盲人位置，新增对称的 `BLIND_LOCATION_UPDATE`（志愿者 WS）推送给志愿者，两者消息格式一致，`orderId`/`lat`/`lng`/`timestamp` 字段相同。
  - **新增走散告警 `ESCORT_DISTANCE_ALERT`**：`IN_PROGRESS` 阶段若双方 GPS 距离连续 2 次采样都超过阈值（默认 100 米，防止单次 GPS 跳变误报）才触发，双方各收到一条 `NOTIFICATION` 消息（`eventType: ESCORT_DISTANCE_ALERT`，带 `ttsText` + `priority: HIGH`）。同一次触发会**并行**走既有紧急升级流程通知客服，二者互不替代。
  - ⚠️ **前端需要处理的新消息类型**：志愿者 WS 新增 `BLIND_LOCATION_UPDATE`；双方 WS 的 `NOTIFICATION` 消息新增 `eventType: ESCORT_DISTANCE_ALERT` 分支。
  - 新增 DB 表 `run_order_track_point`；**生产部署前必须先执行建表 SQL**（`ddl-auto=validate`，见 `CLAUDE.md` "Production Deployment" 章节）。
  - **走散检测信号缺失兜底**：此前对方 GPS 信号缺失（Redis key 过期/未上报）时走散检测会静默跳过，等同于志愿者只要关闭定位就能绕过监控。现在连续 2 次检测到信号缺失也会触发告警 + 应急升级，与距离超阈值走同一套"连续确认"防抖逻辑（互不干扰的独立计数器）。⚠️ **双方 WS 的 `NOTIFICATION` 消息新增 `eventType: ESCORT_SIGNAL_LOST` 分支**（文案：暂时无法获取对方位置，正在为你确认安全）；生产部署前需手动插入该模板两行数据（`data.sql` 不在生产跑，见 `CLAUDE.md`）。
  - **轨迹数据留存策略**：`run_order_track_point` 新增每日定时清理任务（`TrackDataRetentionScheduler`，凌晨 3 点），默认保留 90 天（`app.track.retention-days`），超期硬删除。详见 `docs/轨迹数据留存策略.md`。
- **`NEW_ORDER` WebSocket 消息新增 `startLatitude`/`startLongitude`**：派单通知现在带订单起跑点经纬度，前端可在地图上标记订单位置（订单实体本就有这俩字段，必填）。
- **`GET /api/volunteer/dispatch-summary` 新增 `totalCompleted` 字段**：累计**完成**订单次数（订单走到 COMPLETED 才算）。
  - ⚠️ 与 `totalAccepted`（接单次数）区分：`totalAccepted` 是点了 ACCEPT 就算（含接了没跑完的），`totalCompleted` 才是真正完成数。
  - 新增 DB 列 `volunteer_profile.total_completed`；**生产部署前必须先执行迁移 SQL**（加列 + 按历史 COMPLETED 订单回填），否则 `ddl-auto=validate` 启动失败：
    ```sql
    ALTER TABLE blind_running.volunteer_profile ADD COLUMN total_completed INT NOT NULL DEFAULT 0;
    UPDATE volunteer_profile vp SET total_completed =
      (SELECT COUNT(*) FROM run_order WHERE volunteer_id = vp.user_id AND status = 'COMPLETED');
    ```
  - 手动完成（finishOrder）+ 超时自动完成（autoComplete）两个入口都 +1。
- **`dispatch-summary` 的 `recentOrders[]` 新增 `startAddress` + `blindName`**：每条近期记录现在带起跑点地址和盲人姓名。

- **🔥 志愿者人脸认证升级为动作活体（InitFaceVerify / SMART 方案）**：原 `ContrastFaceVerify` 静态照片比对（`Model=NO_LIVENESS`，照片翻拍可过）替换为阿里云 `InitFaceVerify`（`productCode=SMART`）+ `DescribeFaceVerify` 两段式真动作活体（眨眼/点头交互发生在前端 H5），安全性提升到动作活体级别。
  - **注册流程从 4 步精简为 3 步**：`BASIC_INFO(含身份证二要素核验) → FACE_VERIFY → TRAINING → COMPLETED`。**删除 step2（身份证正反面照片上传）**，姓名+身份证号挪到 step1（`BasicInfoRequest` 新增 `idCardName`/`idCardNumber`），提交即调 `Id2MetaVerify` 自动核验。
  - **step3 改为两段式**（前端轮询拉结果）：
    - `POST /api/volunteer/registration/step3/face-verify/init`（`{ metaInfo }`）→ 返回 `{ certifyId, certifyUrl, status, message }`，前端打开 `certifyUrl` 做动作。
    - `POST /api/volunteer/registration/step3/face-verify/result`（`{ certifyId }`）→ 返回 `{ passed, status, message }`，`status` ∈ `APPROVED`/`REJECTED`/`PENDING`（PENDING 时前端继续轮询）。
    - **防越权**：`result` 接口校验入参 `certifyId` 必须等于该用户 profile 上次 init 落库的 `faceVerifyCertifyId`，防止伪造他人 certifyId 查结果。
    - **删除**旧 multipart 端点 `POST /step3/face-verify`（上传自拍）。
  - ⚠️ **破坏性前端变更**：step2 整段删除；step3 从「上传一张自拍」变成「init 拿 URL → 前端做动作 → result 轮询」。前端必须改造（用户已确认接受）。
  - ⚠️ **生产 DB 迁移**（生产 `ddl-auto=validate`，部署前必须执行）：
    ```sql
    ALTER TABLE volunteer_profile ADD COLUMN face_verify_certify_id VARCHAR(64) NULL;
    -- 三个废弃列（id_card_front_url/id_card_back_url/face_photo_url）暂不 DROP，留一个 release 的回滚窗口
    ```
  - ⚠️ **新增环境变量**：`ALIYUN_CLOUDAUTH_SMART_SCENE_ID`（SMART 方案的 scene id，需在阿里云控制台为 SMART **单独新建** scene，现有 ContrastFaceVerify 的 scene 1000018059 大概率不能复用）、`FACE_VERIFY_RETURN_URL`（动作活体完成后阿里云回跳地址）。
  - 配置项：`face-verify.provider`（`aliyun` 默认 / `test` 测试桩）、`app.face-verify.scene-id`、`app.face-verify.return-url`。
  - 盲人 `verifyIdentity`（`Id2MetaVerify` 二要素）**不变**。
  - 新增 17 个单元测试（`TestFaceVerifyServiceImplTest` certifyId 协议 5 个 + `VolunteerRegistrationServiceTest` 状态机/越权/迁移/幂等 12 个），全量回归通过。

- **WebSocket 消息新增 `messageId`（离散告警去重）+ 服务端主动断连（心跳超时清理）**：
  - **所有 `NOTIFICATION` 类消息（`APP_NOTIFICATION`/`ORDER_STATUS_CHANGED`/`EMERGENCY_VOLUNTEER_ALERT`/`EMERGENCY_RESOLVED_BY_VOLUNTEER`/`EMERGENCY_CONTACT_NOTIFIED`/`ESCORT_DISTANCE_ALERT`/`ESCORT_SIGNAL_LOST` 等）新增 `messageId`（UUID 字符串）字段**，前端可用它去重同一条消息的重复投递（如断线重连后的重发）。`NEW_ORDER`/`PONG`/位置更新类消息（`VOLUNTEER_LOCATION_UPDATE`/`BLIND_LOCATION_UPDATE`）**不受影响**，这些是覆盖式最新值，语义上不需要去重。
  - ⚠️ **服务端可能主动断开空闲连接**：若某条 WS 连接超过 `app.websocket.dead-connection-timeout-seconds`（默认 90 秒）未发送**任何**消息（不限于 PING），服务端会主动关闭该连接（关闭码 `SESSION_NOT_RELIABLE`）。前端按已有的断线重连机制处理即可，**无需**当作错误特殊处理；若客户端本身长时间没有其他消息可发，建议按 30 秒左右周期发 PING 保活。
  - 详见 [`docs/websocket-protocol.md`](websocket-protocol.md) 1.4 节 + 各消息示例。

- **`GET /api/orders/{id}/track` 响应新增 `status` 字段**：返回订单当前状态（`OrderStatus` 枚举值），用于区分空/短轨迹的三种场景——① `PENDING_MATCH`~`DRIVER_ARRIVED` 阶段空轨迹是正常的（陪跑还没开始）；② `IN_PROGRESS` 阶段空/短轨迹是"采集中，数据不足"；③ 终态（`COMPLETED`/`CANCELLED` 等）+ 空轨迹且订单较早创建，则是"历史订单不支持轨迹功能"。前端无需再自行猜测或额外请求订单详情来做这个判断。

### 明确不做

- **积分系统（`pointsBalance`/`pointsDelta`）**：项目当前无积分系统，`dispatch-summary` 与 `recentOrders` 均**不返回**积分字段。前端首页暂不展示积分，待产品定义积分规则后单独评估。

- **志愿者首页聚合接口 `GET /api/volunteer/dispatch-summary`**：一次返回首页所需的全部数据，前端无需多发请求拼装。
  - 接单资格：`canDispatch` + 结构化不可接单原因 `notAvailableReasons`（`DISPATCH_DISABLED`/`NOT_VERIFIED`/`REGISTRATION_INCOMPLETE`/`OFFLINE`，前端可精确引导）+ `wantsDispatch` 开关状态
  - 在线与位置：`isOnline` + `lastLat`/`lastLng`/`lastLocationAt`（离线时为 null，前端据此决定是否画范围圆）
  - 覆盖范围与时段：`coverageRadiusKm`（当前固定 10）+ `isWithinServiceTime` + `availableTimeSlots`
  - 评分与统计：`avgRating`/`totalRatings`/`totalDispatched`/`totalAccepted`/`totalDeclined`/`totalTimeout`/`acceptanceRate`
  - 订单：`activeOrders`（活跃订单全量）+ `recentOrders`（最近 5 条）
  - 盲人手机号一律脱敏；近期评分走批量查询防 N+1
  - ⚠️ **本项目无积分系统**，本接口不返回积分字段
  - 完整字段说明见 [`docs/volunteer-dispatch-summary.md`](volunteer-dispatch-summary.md)，前端交接以此为准

### 文档

- **新增 Claude Code + Postman 协同测试工作流**：[`docs/claude-code-postman-workflow.md`](claude-code-postman-workflow.md)。覆盖三个核心场景:
  - `/postman:sync` —— `api_spec.yaml` 改动后自动同步到 Postman collection(避免手动导入)
  - `/postman:test` —— 跑 collection + 失败归因(关联 git diff 代码变更给修复建议)
  - `/postman:security` —— 针对本项目 JWT 多角色 / 限流 / 手机号脱敏 / Token 黑名单 / CS 登录锁定的 OWASP 专项审计
  - 含订单状态机串联测试示例、4 角色 token 获取模板、Redis key 速查、错误码映射、生产环境红线(禁破坏性测试)
- 文档第 2.3 节收录社区 [springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) 插件(可选;本项目已有 `everything-claude-code` 覆盖 `java-reviewer`/`security-reviewer`/`code-reviewer`,`spring-data-jpa` 专项可按需补装)。

### 修复

- **登出（`POST /api/auth/logout` / `POST /api/cs/auth/logout`）此前会误撤销同账号其他仍有效的 Token，现已修复为只撤销当前 Token**：典型触发场景——登录拿 Token A → `POST /api/user/role` 选角色拿替换 Token B → 用 Token A 调登出 → Token B 也随之失效（401）。根因是登出黑名单机制此前按用户整体撤销（S1，2026-06-17），只要 Token 签发时间早于登出时刻就会被一并吊销。
  - **✅ 已确认契约**：登出的预期范围就是"仅当前 Token"，与前端/iOS 原有假设一致，**无需改动客户端行为**。
  - 账号注销（`DELETE /api/users/{id}`）仍然会撤销该账户**全部** Token，这一行为不变——注销和登出走的是两套独立机制。
  - **`DELETE /api/users/{id}` 成功响应新增字段**：`phoneReusable: true`、`allTokensInvalidated: true`（原来只有 `success: true`）。
  - **活动订单阻止注销的 errorCode 更正**：从通用的 `ORDER_STATUS_NOT_ALLOWED` 改为专用的 `ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED`（HTTP 状态码仍是 409），前端可据此精确区分该场景。
  - 纯后端修复，登出/注销的请求体不变，前端无需改动请求逻辑；仅注销响应新增了上述两个字段，前端如需展示可读取。
  - ⚠️ **尚未部署到生产环境**，本地已修复并通过单元测试，待部署后按检查清单复测。

- **志愿者 Step3 动作活体人脸认证（发起认证）此前 100% 失败，现已修复**：调用 `POST /api/volunteer/registration/step3/face-verify/init` 发起动作活体认证时，服务端调用阿里云动作活体接口遗漏了必填的用户标识参数，导致阿里云侧参数校验不通过、所有用户的发起请求均失败。现已补全该参数，问题解决。
  - 纯后端修复，不改变客户端请求体/接口契约，前端无需改动。
  - ⚠️ **注意**：此前所有尝试发起 Step3 动作活体认证的用户都会遇到此问题；如果之前测试/使用失败，可重新尝试。
  - 本次为该功能区（Step3 动作活体人脸认证）当天的第二次修复，与此前同日修复的 `ProductCode` 取值问题（`SMART` → `LR_FR`）根因不同、彼此独立。

## [1.5.0] - 2026-06-22

### 前端联调反馈修复（6 项）

本次响应前端联调反馈，修复 6 项后端问题。⚠️ **含 API 契约变更**，前端需配合升级。

#### ⚠️ 破坏性变更（前端必读）

1. **统一错误响应结构**：`AuthException`（验证码错误等）返回体从旧的 `{"error": "..."}` 改为统一结构
   ```json
   { "success": false, "code": 400, "errorCode": "INVALID_VERIFICATION_CODE", "message": "验证码错误或已过期" }
   ```
   `OrderStatusException`（接单失败等）响应体新增 `errorCode` 字段。错误码集中在 `ErrorCode` 枚举管理。

2. **志愿者可服务状态字段** `wantsDispatch`：
   - `GET /api/volunteer/profile` 响应新增 `wantsDispatch` 字段（boolean）
   - `PUT /api/volunteer/profile` 支持传 `wantsDispatch` 切换可服务状态（PATCH 语义，未传保留原值）
   - 关闭可服务状态（false）时，志愿者可浏览订单但**接单会被拒绝**（403 + `errorCode: VOLUNTEER_NOT_AVAILABLE`）

3. **预约提前量校验**：创建订单时 `plannedStartTime` 必须距当前时间 ≥ 30 分钟（`app.order.min-lead-time-minutes` 可配），否则返回 HTTP **422** + `errorCode: APPOINTMENT_TOO_SOON`

4. **紧急联系人电话明文返回**：`GET /api/users/{me}/emergency-contacts` 返回**明文**电话（接口仅限本人访问）。前端原先展示的掩码需自行处理展示层脱敏。`PUT` 更新改为 PATCH 语义：未传 `phone` 字段保留原值。

#### 各项详情

| # | 问题 | 修复 | 文件 |
|---|------|------|------|
| 1 | `isAvailable` 开启后查询未生效 | `wantsDispatch` 落库到 `volunteer_profile` 表；`GET/PUT /profile` 读写；派单候选筛选 + 接单校验均以 DB 为准 | `VolunteerProfile`, `VolunteerService`, `VolunteerLocationService`, `DispatchService` |
| 2 | 接单失败缺统一错误码 | 新建 `ErrorCode` 枚举；`OrderStatusException` 加 `errorCode`；接单各分支返回 `ORDER_ALREADY_ACCEPTED`/`ORDER_DISPATCH_MISMATCH`/`ORDER_CONCURRENT_CONFLICT` | `ErrorCode`, `OrderStatusException`, `GlobalExceptionHandler`, `DispatchService` |
| 3 | 不足 30 分钟预约仍创建成功 | 新建 `OrderTooSoonException` → HTTP 422 + `APPOINTMENT_TOO_SOON`；阈值 `app.order.min-lead-time-minutes=30` | `OrderTooSoonException`, `OrderCreationService`, `application.properties` |
| 4 | 验证码错误返回 `{"error":...}` | `AuthException` 加 `errorCode`；统一返回 `{success,code,errorCode,message}`；新增 `INVALID_VERIFICATION_CODE`/`PHONE_FORMAT_INVALID`/`USER_NOT_FOUND` | `AuthException`, `AuthService`, `GlobalExceptionHandler` |
| 5 | 本人读紧急联系人电话被掩码 | `toResponse` 返回明文；`updateContact` 改 PATCH 语义；放宽 `@NotBlank` 由 service 手动校验新增必填 | `EmergencyContactService`, `EmergencyContactRequest` |
| 6 | `DRIVER_ARRIVED → IN_PROGRESS` 无触发方 | 明确"到达即开始"语义：`DRIVER_ARRIVED` 视为服务进行中可直接 `/finish`；修复 `findTimedOutOrders` 让卡在 `DRIVER_ARRIVED`/`DRIVER_EN_ROUTE` 的超时订单也能自动完成 | `OrderStatus`, `RunOrderRepository`, `OrderTimeoutScheduler` |

#### 顺手加固

- `OrderFlowTest.TC-ORDER-01` 改用轮询等待 `IN_PROGRESS`，消除异步时序导致的 flaky。
- 新增 `EmergencyContactApiTest`（3 用例）、`OrderValidationTest` 补 2 用例（提前量 422/通过）。

#### ⚠️ 数据库迁移（生产部署必做）

`volunteer_profile` 表新增 `wants_dispatch` 列。生产环境（`ddl-auto=validate`）首次部署前需手动执行：
```sql
ALTER TABLE volunteer_profile ADD COLUMN wants_dispatch BOOLEAN NOT NULL DEFAULT TRUE;
```

---

## [1.4.3] - 2026-06-20

### 缺陷修复 — 安全 / 异常处理（B1/C1）

- **B1 GlobalExceptionHandler `handleIllegalState` 状态码错误**：原 handler 把所有 `IllegalStateException` 映射到 HTTP 429 并直接返回 `e.getMessage()`，导致志愿者状态错误（本应 409）、联系人缺失（本应 400）等均返回 429，且内部业务文本泄露给前端。修复方案：`EmergencyService.triggerEmergency` 冷却检测改用 `RateLimitException(cooldownSeconds)` 替代 `IllegalStateException`（享有已有的 429 + `Retry-After` handler）；`handleIllegalState` 改为返回 HTTP 500 + 通用消息"服务器内部错误，请稍后重试"（不暴露内部文本）。

- **C1 SecurityConfig 缺少 `POST /api/orders/*/call/initiate` 显式授权**：该端点落入 `anyRequest().authenticated()`，意味着 CS 客服账号也可调用。加 `requestMatchers(HttpMethod.POST, "/api/orders/*/call/initiate").hasAnyRole("BLIND", "VOLUNTEER")`（与 S3 教训一致，必须用 HttpMethod 重载）。

### 补充配置文档（C2）

- **application.properties** 补全 `app.match.timeout-seconds=300`、`app.rematch.timeout-seconds=300` 两个配置键的显式文档，便于运维调整匹配和重派超时阈值。

### T1 接口补测完成（2026-06-20）

以下 6 个接口已在生产服务器完成测试（通过 `sms.test-phones` 白名单，代码 000000，无需真实短信）：

| 接口 | 测试结果 |
|------|---------|
| `PUT /api/orders/{id}/keep-waiting` | ✅ 200 |
| `POST /api/orders/{id}/call/initiate` | ✅ 200（mock 返回 CONNECTED + 虚拟号码） |
| `GET /api/orders/{id}/call/records` | ✅ 200 |
| `GET /api/blind/volunteer-location` | ✅ 200（DRIVER_EN_ROUTE 状态返回 lat/lng） |
| `PUT /api/cs/emergency-events/{id}/notify-contact` | ✅ 200（E1 修复后验证） |
| `PUT /api/cs/emergency-events/{id}/false-alarm` | ✅ 200 |

至此，除阿里云上传/人脸认证外的所有 REST 接口均已在生产环境冒烟测试通过。

### ⚠️ 已知问题（本次新发现，待修复）

- **SMS-A3 EMERGENCY_ALERT 模板参数格式被拒**：`location` 变量（坐标降级格式）和 `time` 变量（ISO 8601 含纳秒）均不符合阿里云模板变量规范（`isv.TEMPLATE_PARAMS_ILLEGAL`），所有紧急短信通知实际发送失败。E1 的 try-catch 保障了接口返回 200，但联系人未收到短信。需修改阿里云 SMS 模板配置（将 location/time 变量类型改为"自定义"）。见 `docs/ISSUES.md` SMS-A3 条目。

⚠️ **注意**：本版 v1.4.3 代码修复已本地构建并通过全量测试（143 个），但因部署工具限制 **新 JAR 尚未上传到生产服务器**（服务器仍运行 v1.4.2）。B1/C1 修复需手动 SCP 后重启服务生效：`scp build/libs/demo-0.0.1-SNAPSHOT.jar root@47.114.113.171:/opt/blindrun/ && ssh root@47.114.113.171 "systemctl restart blindrun"`。

---

## [1.4.2] - 2026-06-20

### 缺陷修复 — 紧急服务 SMS 异常（E1）

- **E1 `notifyContact`/`resolveEvent` SMS 未捕获**：`notifyContact()` 和 `resolveEvent()` 对 SMS 调用均无 try-catch。阿里云 SMS 失败时异常冒泡触发 `@Transactional` 回滚，接口返回 500 且状态变更（`CONTACT_NOTIFIED` / `RESOLVED`）同时丢失。对两处 SMS 调用各加 try-catch，失败仅记 error 日志，DB 事务正常提交。同一类的 `escalateToEmergencyContacts` 已在 v1.4.0（commit `a88b699`）修复，本次补全两处遗漏。

### 生产接口冒烟测试（2026-06-19 ~ 06-20）

本次对除阿里云上传/人脸相关接口外的全部接口做了生产环境冒烟测试，共覆盖约 50 个端点，均返回 2xx / `success:true`：

| 测试分组 | 覆盖内容 | 结果 |
|---------|---------|------|
| 基础信息 | `GET/PUT /api/auth/me`、`/api/blind/profile`、`/api/volunteer/profile`、dispatch-status、registration/status、`GET /api/users/{id}` | ✅ 全通 |
| 紧急联系人 CRUD | `GET/POST/PUT/DELETE /api/users/{id}/emergency-contacts`、`set-primary` | ✅ 全通 |
| 订单查询 | `GET /api/orders/mine`（双角色）、status-logs、available、review、reviews | ✅ 全通 |
| 订单派单 E2E | 创建→PENDING_MATCH→PENDING_ACCEPT→IN_PROGRESS→DRIVER_EN_ROUTE→DRIVER_ARRIVED→COMPLETED（订单 19/22/24） | ✅ 全通 |
| 位置上报 | `POST /api/blind/location`、`POST /api/volunteer/location`（WebSocket 路径） | ✅ 全通 |
| 紧急 SOS | 触发→志愿者响应→CS accept→CS resolve（E1 修复后）、独立 SOS（无订单） | ✅ 全通 |
| CS + Admin | CS 登录/登出、通知模板 GET/PUT、志愿者审核列表、培训课程 CRUD、软删除用户 | ✅ 全通 |

### 验证

生产测试 `PUT /api/cs/emergency-events/7/resolve` → HTTP 200（修复前 500）。

---

## [1.4.1] - 2026-06-19

### 缺陷修复 — 派单竞态条件（D1/D2）

- **D1 MatchingService 事务后触发**：`handleOrderCreated` 从 `@EventListener @Async` 改为 `@TransactionalEventListener(AFTER_COMMIT) @Async`。修复「订单 X 未找到，跳过匹配」——事务提交前异步线程读 DB，订单尚不存在，派单流程静默丢失。
- **D2 OrderLifecycleService REQUIRES_NEW 传播**：`onDispatchAccepted` 从 `@EventListener @Async @Transactional` 改为 `@TransactionalEventListener(AFTER_COMMIT) @Async @Transactional(propagation=REQUIRES_NEW)`。修复接单后状态仍为 PENDING_MATCH（异步线程在事务提交前执行，读到旧状态），以及直接使用 `AFTER_COMMIT` 加默认 `@Transactional` 导致的**启动崩溃**（Spring 规定 `@TransactionalEventListener` 的 `@Transactional` 必须 `REQUIRES_NEW` 或 `NOT_SUPPORTED`）。

### 验证

生产 E2E 全流程（订单 19）：创建→PENDING_MATCH→PENDING_ACCEPT→IN_PROGRESS→DRIVER_EN_ROUTE→DRIVER_ARRIVED→COMPLETED，全部状态正确流转。

---

## [1.4.0] - 2026-06-17

### 缺陷修复 — 安全（S1/S3/S4，代码评审 P0/P1）

- **S1 Token 黑名单按签发时间比对**：黑名单存「拉黑时刻时间戳」，`isBlacklisted` 比对 token `iat ≤ 拉黑时刻` 才拦截。修复登出→重登被旧黑名单锁死最长 24h 的问题。
- **S3 SecurityConfig 订单授权 HttpMethod 重载**：原 `"POST /api/orders"` 字符串在 Spring Security 6 的 `MvcRequestMatcher` 下方法前缀全部失效（导致 VOLUNTEER 可创建订单等越权），改用 `requestMatchers(HttpMethod, pattern)` 重载；`/cancel` 改 `hasAnyRole(BLIND, VOLUNTEER)`（修正过严——`cancelOrder` 支持志愿者取消转 REMATCHING）。
- **S4 限流取真实客户端 IP**：启用 `server.forward-headers-strategy=native`（Tomcat RemoteIpValve），`extractClientIp` 改用 `getRemoteAddr()`，杜绝 `X-Forwarded-For` 伪造绕过限流。

### 缺陷修复 — 订单/紧急（S2/B2/A1/A2/S5，代码评审 P0/P1）

- **S2 REMATCHING 重派上限**：`MAX_REMATCH_COUNT=3`，超限自动 `CANCELLED` + `CancelledBy.SYSTEM` + 通知盲人。
- **B2 旧 `/accept`、`/reject` 复用 `/respond` 派单校验**：杜绝绕过串行派单抢单；入口加志愿者资质校验（未认证→`OrderPermissionException` 403）；接单失败 `IllegalStateException`→`OrderStatusException`(409)。
- **A1 紧急 SOS `orderId` 可空**：无活跃订单也能求救。
- **A2 紧急位置三级降级**：逆地理编码文字地址（高德 regeo，2s 超时）→ 可读经纬度 → 求助引导；短信禁链接（违反阿里云+运营商规定）。
- **S5 无紧急联系人反馈**：明确通知盲人「未找到联系人，已转客服」+ 事件转 `CS_HANDLING`（防重复 escalate）。

### 新增
- `docs/ISSUES.md`：问题追踪文档（已解决/待处理），与 CHANGELOG 分工——ISSUES 记缺陷编号与修复状态，CHANGELOG 记版本发布。

### ⚠️ 升级注意（破坏性/行为变化）
- 旧 `/accept` 接单从「同步 `IN_PROGRESS`」变为「`PENDING_ACCEPT` → 异步 `IN_PROGRESS`」，前端请确认不依赖接单后立即 `IN_PROGRESS`。
- 生产部署必须：① 加 `server.forward-headers-strategy=native`（否则限流塌缩到 Nginx 单 IP）；② `ALTER TABLE emergency_events MODIFY order_id BIGINT NULL`；③ 配 `AMAP_WEB_KEY`（可选，未配则紧急短信降级为经纬度）。

---

## [1.3.0] - 2026-06-08

### 新增 — 高并发优化

- **Redis Lua 原子限流**：`RateLimitInterceptor` 改用 Lua 脚本原子执行 INCR+EXPIRE，消除 SessionCallback 两步之间服务崩溃导致 key 永不过期的竞态条件
- **有界异步线程池**：新增 `AsyncConfig`，替换 Spring 默认的 `SimpleAsyncTaskExecutor`（每次调用创建新线程），改用 core=10/max=50/queue=200 的有界池，队满时触发 CallerRunsPolicy 背压
- **乐观锁重试**：`OrderLifecycleService.acceptOrderWithRetry()` 从立即抛出改为指数退避重试3次（50ms/100ms/150ms），彻底消除并发接单时的误报错误
- **阿里云 SDK 超时**：`AliyunIdVerifyService` 和 `AliyunSmsServiceImpl` 添加 connectTimeout=5s / readTimeout=10s，防止外部服务无响应时线程永久阻塞

### 变更 — 生产配置优化

- **HikariCP 连接池**：从 50 连接调整为 10（2核服务器最优值），避免 MySQL 在 2 核上频繁上下文切换
- **关闭生产 SQL 日志**：`spring.jpa.show-sql` 默认改为 `false`，消除高并发下每条 SQL 写日志的磁盘 I/O 压力
- **JVM 内存上限**：服务器 ExecStart 添加 `-Xms512m -Xmx1500m -XX:+UseG1GC -XX:MaxGCPauseMillis=200`，防止 JVM 无上限扩展占用 MySQL/Redis 内存，G1GC 将 GC 停顿从可能的 5s 压缩到 200ms 以内

### 修复 — 代码清理

- 删除 `application.properties` 中与 `AsyncConfig` 冲突的无效 `spring.task.execution.*` 配置（被 AsyncConfig Bean 覆盖后这些属性不生效）
- `OrderController` 的 `/accept` 和 `/reject` 端点补充 `@Deprecated` 注解

---

## [1.2.0] - 2026-04-24

### 新增

- **登出接口**：`POST /api/auth/logout`、`POST /api/cs/auth/logout`，JWT 加入 Redis 黑名单
- **设置角色返回新 Token**：`POST /api/user/role` 响应新增 `token` 字段，客户端必须替换本地存储的 token
- **RBAC 角色权限**：SecurityConfig 按角色精确控制所有接口，无权限返回 JSON 格式 403
- **WebSocket 角色校验**：盲人只能连 `/ws/blind`，志愿者只能连 `/ws/volunteer`，连错返回 403
- **CS 账号登录锁定**：连续失败5次锁定15分钟，通过 Redis 原子操作实现
- **手机号格式校验**：登录/发验证码只接受1开头11位中国手机号

### 变更

- **401/403 统一 JSON 格式**：不再返回 HTML，统一返回 `{success, code, message}`

---

## [1.1.0] - 2026-04-22

### 新增

- **串行派单系统**：`DispatchService` + `ScoringService` + `DispatchScheduler`，5维评分（距离×40 + 时间×25 + 评分×20 + 接单率×10 + 配速×5），3轮距离扩展（5→10→20km），每志愿者30秒超时轮转
- **志愿者响应接口**：`POST /api/orders/{id}/respond {"action":"ACCEPT"|"DECLINE"}`，替代旧的 `/accept` 和 `/reject`
- **OSS 文件云存储**：`OssFileStorageService` 接入阿里云 OSS，私有 Bucket + 1小时 presigned URL；通过 `app.storage.type` 切换本地/OSS
- **盲人 WebSocket**：新增 `/ws/blind` 端点，`BlindWebSocketHandler` 支持位置上报（LOCATION_UPDATE）和 PING
- **阿里云人脸认证完善**：`AliyunIdVerifyService` 添加 `Model=NO_LIVENESS`/`Crop=T`、照片自动压缩(<1MB)、错误码中文映射，不再是 Stub
- **REMATCHING 状态**：志愿者取消后不直接 CANCELLED，进入 REMATCHING 重新派单

### 变更

- 订单状态从7种扩展为9种：新增 `REMATCHING`、`NO_VOLUNTEER`
- 订单字段新增：配速/路线/导盲犬/时长/备注
- 盲人档案新增：视力/牵引/聊天偏好/默认配速
- 志愿者档案新增：导盲犬/配速范围

---

## [1.0.0] - 2026-04-14

### 新增

#### 核心功能
- 实现完整的订单生命周期管理
  - 订单创建、匹配、接单、取消、完成
  - 7种订单状态流转
  - 乐观锁防止并发接单
- 实现志愿者注册流程（4步）
  - STEP_1: 基本信息
  - STEP_2: 身份证上传（管理员审核）
  - STEP_3: 人脸验证（当前为Stub实现）
  - STEP_4: 培训课程和测验
- 实现紧急求助系统
  - 盲人触发紧急求助
  - 志愿者响应（30秒超时）
  - 客服介入处理
  - 紧急联系人通知
- 实现实时匹配系统
  - 基于地理位置的志愿者匹配
  - Redis位置缓存（30秒TTL）
  - 最多推送3名志愿者
- 实现WebSocket实时通信
  - 订单推送
  - 通知推送
  - 紧急求助推送
  - 多角色会话管理

#### 认证授权
- JWT无状态认证
- 短信验证码登录（6位，5分钟有效期）
- 客服用户名密码登录（BCrypt加密）
- 角色权限管理（BLIND/VOLUNTEER/CS/ADMIN）
- 软删除用户（deletedAt字段）

#### 档案管理
- 盲人用户档案（姓名、配速、特殊需求）
- 志愿者档案（姓名、电话、认证状态）
- 紧急联系人管理（1-5个，支持主联系人）

#### 位置服务
- 志愿者位置上报（Redis + MySQL双写）
- 盲人位置上报
- 接近检测（100米阈值）
- 位置脱敏（手机号隐藏）

#### 通知系统
- 数据库驱动的通知模板
- WebSocket实时推送
- 短信通知（当前为模拟实现）
- 支持TTS文本和优先级

#### 评价系统
- 订单完成评价（1-5星）
- 双向评价（盲人→志愿者，志愿者→盲人）
- 评论文本记录

#### 隐私号通话
- 阿里云隐私号集成（可配置）
- Mock实现（返回虚拟号码）
- 通话记录存储

#### 管理功能
- 管理员审核志愿者身份证
- 培训课程CRUD
- 通知模板管理
- 客服处理紧急事件

#### 定时任务
- 订单超时自动完成（60秒）
- 紧急求助超时检测（10秒轮询）
- 重新匹配超时提醒（10秒轮询）
- 匹配超时提醒（10秒轮询）
- 订单超时提醒（60秒轮询）

#### 限流保护
- Redis限流（3个桶）
  - auth: 10次/分钟
  - registration: 20次/分钟
  - general: 60次/分钟
- HTTP 429响应
- Retry-After头

#### 测试
- 116个测试用例
  - 113个常规测试
  - 3个慢速测试（OrderTimeoutTest×2, ConcurrencyTest）
- Testcontainers集成测试
- 软删除一致性测试
- 限流集成测试

#### 文档
- Swagger UI集成
- OpenAPI 3.0规范
- 完整的中文代码注释

### 技术栈

#### 后端框架
- Spring Boot 3.4.4
- Spring Security 6.x
- Spring Data JPA
- Spring WebSocket

#### 数据库
- MySQL 8.x（主数据库）
- Redis 6.x（缓存、限流、位置）
- H2（测试数据库）

#### 认证
- JWT (io.jsonwebtoken:jjwt-api:0.12.5)
- BCrypt密码加密

#### 文档
- SpringDoc OpenAPI 2.8.6

#### 测试
- JUnit 5
- Testcontainers 2.0.4
- Jedis（测试Redis客户端）

#### 工具
- Lombok
- Gradle 8.x
- Git

### 配置项

#### 匹配算法
- `app.matching.max-distance-km`: 最大匹配距离（默认10km）
- `app.matching.max-candidates`: 最多推送志愿者数（默认3）

#### WebSocket
- `app.websocket.endpoint`: WebSocket端点（默认/ws/volunteer）

#### 位置服务
- `app.volunteer.location-ttl-seconds`: 志愿者位置TTL（默认30秒）
- `app.proximity.threshold-meters`: 接近阈值（默认100米）

#### 紧急事件
- `app.emergency.cooldown-seconds`: 冷却时间（默认60秒）
- `app.emergency.volunteer-timeout-seconds`: 志愿者超时（默认30秒）

#### 限流
- `rate-limit.enabled`: 是否启用限流（默认true）
- `rate-limit.auth.max-requests`: 认证限流（默认10/分钟）
- `rate-limit.registration.max-requests`: 注册限流（默认20/分钟）
- `rate-limit.general.max-requests`: 通用限流（默认60/分钟）

### 数据库表

#### 用户相关（7张）
- users（用户表）
- blind_profiles（盲人档案）
- volunteer_profiles（志愿者档案）
- volunteer_locations（志愿者位置）
- volunteer_available_times（志愿者可用时间）
- emergency_contacts（紧急联系人）
- cs_users（客服用户）

#### 订单相关（7张）
- run_orders（订单表）
- order_status_logs（订单状态日志）
- order_reviews（订单评价）
- emergency_events（紧急事件）
- emergency_notifications（紧急通知记录）
- call_records（通话记录）
- notification_logs（通知日志）

#### 系统相关（5张）
- notification_templates（通知模板）
- training_courses（培训课程）
- training_progress（培训进度）
- training_quiz_questions（测验题目）
- training_quiz_attempts（测验记录）

### API端点

#### 认证（3个）
- POST /api/auth/send-code
- POST /api/auth/verify-code
- GET /api/auth/me

#### 角色管理（1个）
- POST /api/user/role

#### 用户管理（2个）
- GET /api/users/{id}
- DELETE /api/users/{id}

#### 盲人用户（2个）
- GET /api/blind/profile
- PUT /api/blind/profile

#### 志愿者（5个）
- GET /api/volunteer/profile
- PUT /api/volunteer/profile
- POST /api/volunteer/verification
- GET /api/volunteer/verification/status
- POST /api/volunteer/location

#### 志愿者注册（8个）
- GET /api/volunteer/registration/status
- POST /api/volunteer/registration/step1
- POST /api/volunteer/registration/step2/id-card
- POST /api/volunteer/registration/step3/face-verify/init
- GET /api/volunteer/registration/training/courses
- POST /api/volunteer/registration/training/progress
- GET /api/volunteer/registration/training/quiz/{courseId}
- POST /api/volunteer/registration/training/quiz/answer

#### 订单（11个）
- POST /api/orders
- POST /api/orders/{id}/accept
- POST /api/orders/{id}/reject
- POST /api/orders/{id}/finish
- POST /api/orders/{id}/cancel
- PUT /api/orders/{id}/keep-waiting
- GET /api/orders/available
- GET /api/orders/{id}
- GET /api/orders/mine
- GET /api/orders/{id}/status-logs
- POST /api/orders/{id}/en-route
- POST /api/orders/{id}/arrived

#### 评价（2个）
- POST /api/orders/{id}/review
- GET /api/orders/{id}/reviews

#### 紧急求助（2个）
- POST /api/emergency/trigger
- PUT /api/emergency/{eventId}/volunteer-response

#### 紧急联系人（5个）
- GET /api/users/{userId}/emergency-contacts
- POST /api/users/{userId}/emergency-contacts
- PUT /api/users/{userId}/emergency-contacts/{contactId}
- DELETE /api/users/{userId}/emergency-contacts/{contactId}
- PUT /api/users/{userId}/emergency-contacts/{contactId}/set-primary

#### 客服（5个）
- POST /api/cs/auth/login
- GET /api/cs/emergency-events
- PUT /api/cs/emergency-events/{id}/accept
- PUT /api/cs/emergency-events/{id}/notify-contact
- PUT /api/cs/emergency-events/{id}/resolve
- PUT /api/cs/emergency-events/{id}/false-alarm

#### 管理员（6个）
- GET /api/admin/notification-templates
- PUT /api/admin/notification-templates/{id}
- GET /api/admin/volunteers/review/id
- POST /api/admin/volunteers/review/id
- GET /api/admin/volunteers/training/stats
- POST /api/admin/volunteers/training/courses
- PUT /api/admin/volunteers/training/courses/{id}
- DELETE /api/admin/volunteers/training/courses/{id}

#### 位置（1个）
- POST /api/blind/location

#### 通话（2个）
- POST /api/orders/{orderId}/call/initiate
- GET /api/orders/{orderId}/call/records

#### WebSocket
- WS /ws/volunteer?token=<jwt>

### 已知问题

#### 待实现功能
- [ ] 真实短信服务集成（当前为Mock实现）
- [ ] 人脸识别系统集成（当前为Stub实现）
- [ ] 阿里云隐私号配置（当前为Mock实现）
- [ ] 文件存储服务（当前使用本地存储）
- [ ] 分布式Session（当前使用JWT无状态）

#### 技术债务
- [ ] WebSocket消息队列（防止消息丢失）
- [ ] 分布式限流（当前为单机限流）
- [ ] 数据库读写分离
- [ ] 缓存预热机制
- [ ] 监控和告警系统

### 贡献者

- Jayden23018

---

## 变更说明格式

- **新增**: 新增的功能
- **变更**: 功能的变更
- **废弃**: 即将移除的功能
- **移除**: 已移除的功能
- **修复**: Bug修复
- **安全**: 安全相关的修复

---

## 版本命名规则

遵循语义化版本（Semantic Versioning）:
- MAJOR.MINOR.PATCH
- MAJOR: 不兼容的API变更
- MINOR: 向下兼容的功能新增
- PATCH: 向下兼容的Bug修复

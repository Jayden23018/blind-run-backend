# 变更日志

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

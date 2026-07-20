# 助盲跑后端 — 问题追踪 (Issues Tracker)

> **用途**：专门记录代码评审发现的问题/缺陷及其修复状态（已解决 / 待处理）。
> **与 `docs/TODO.md` 的区别**：`TODO.md` 是**功能开发路线图 + 技术债务**（要做什么新功能）；本文件专记**缺陷修复进度**（已有功能的问题）。
> **维护规则**：
> - 修复并验证后，将条目从「待处理」移至「已解决」，附日期 + 涉及文件。
> - 新评审发现的问题，按优先级（P0 > P1 > P2）追加到「待处理」对应区块。
> - 每条标注信息可信度：**【已确证】**（读代码/文档确认）或 **【⚠️ 待核实】**（概括，需进一步核实）。

**最近更新**：2026-07-19（安全审计整改：紧急事件 GPS 坐标脱敏 + 盲人离线位置未清理两处 CONFIRMED 缺陷、CS_ADMIN 原始坐标查看权限、GPS 90 天留存清理、志愿者可见盲人姓名脱敏）

**2026-07-18**：PR1：账号注销流程加固——活跃订单校验补全（盲人侧遗漏 `DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`、志愿者侧此前完全没有校验）+ PII 级联清理（`BlindProfile`/`VolunteerProfile`/`EmergencyContact`/`RunOrderTrackPoint`/OSS 证件照片），新增 `UserServiceTest`；此前"账号注销功能整体缺失"的表述系误判，现予更正

---

## 状态总览

| 优先级 | 已解决 | 待处理 |
|--------|--------|--------|
| P0（影响核心功能/安全） | ✅ 12 / 12 | 0 |
| P1（重要，应修） | ✅ 20 / 20 | 0 |
| P2（增强/优化） | ✅ 9 / 9 | 2（T2/T3 文档缺口） |

**评审来源**：2026-06-17 首次全面代码评审。

### 编号约定
- `S` = Security 安全　`A` = Availability / Accessibility 可用性·可达性　`B` = Business 业务逻辑　`V` = Volunteer 志愿者　`P` = Platform 平台·部署

---

## ✅ 已解决

### [P0] S13 · 紧急事件 WS 广播泄露原始 GPS 给全体客服 — `2026-07-19` 【已确证】

- **问题**：`NotificationService.sendEmergencyAlert()` 向 `sessionRegistry.sendToCs()`（所有在线客服，不区分 CS/ADMIN）广播时直接带 `gpsLat`/`gpsLng` 原始坐标，与 REST 接口 `EmergencyEventResponse`（已用 `hasGpsLocation` 布尔值脱敏）不一致。
- **影响**：普通客服（非 ADMIN）本不该看到盲人原始位置，WS 推送绕过了 REST 层已有的脱敏设计；`/ws/cs` 端点当前未注册（`WebSocketConfig` 未配置），此推送目前实际不可达，但属于设计一致性/纵深防御问题。
- **方案**：`sendEmergencyAlert()` 改为推送 `hasGpsLocation` 布尔值，不含原始坐标；`sendEmergencyVolunteerAlert()`（推给志愿者本人）保留原始坐标不变（志愿者需要坐标定位盲人）。
- **涉及文件**：`NotificationService.sendEmergencyAlert`
- **验证**：`gradlew compileJava` + `gradlew test` 全量通过。

### [P0] S14 · 盲人 WebSocket 断开未清理 Redis 位置缓存 — `2026-07-19` 【已确证】

- **问题**：`BlindWebSocketHandler.afterConnectionClosed()` 只注销 session，未清理 `blind:loc:{userId}` Redis key；志愿者侧断开时 `VolunteerLocationService.setOffline()` 会清理对应 key，两侧不对称。
- **影响**：盲人断线后，`blind:loc:{userId}` 仍存活到 TTL（30 秒）到期才失效，期间陪跑中的志愿者/客服可能读到过期位置。
- **方案**：新增 `BlindLocationService.clearLocation(userId)`（`redisTemplate.delete`），在 `afterConnectionClosed()` 中调用，与志愿者侧对称。
- **涉及文件**：`BlindLocationService.clearLocation`、`BlindWebSocketHandler.afterConnectionClosed`
- **验证**：`gradlew compileJava` + `gradlew test` 全量通过。

### [P1] S15 · 志愿者端泄露盲人真实姓名（手机号已脱敏，姓名未脱敏） — `2026-07-19` 【已确证】

- **问题**：`VolunteerService.toActiveOrder()`/`toRecentOrder()`（供 `GET /api/volunteer/dispatch-summary` 用）中 `blind.getName()` 原样返回，同一 DTO 里手机号已经过 `PhoneMaskUtils.mask()` 脱敏，姓名却遗漏。
- **影响**：志愿者可看到服务对象的完整真实姓名，与手机号脱敏的隐私最小化原则不一致。
- **方案**：新增 `NameMaskUtils.mask()`（保留首字符，其余替换为 `*`，1 字符姓名整体替换为 `*`），应用于 `toActiveOrder()`/`toRecentOrder()` 两处 `blindName` 字段。
- **涉及文件**：`util/NameMaskUtils.java`（新增）、`VolunteerService.toActiveOrder`、`VolunteerService.toRecentOrder`
- **验证**：`gradlew compileJava` + `gradlew test` 全量通过。

### [P1] S16 · 客服管理员（CS_ADMIN）新增原始 GPS 坐标查看权限 — `2026-07-19` 【已确证】

- **背景**：紧急事件坐标此前对所有客服统一脱敏（`hasGpsLocation` 布尔值），但管理员处理升级事件（如联系家属/报警）时需要精确坐标。
- **方案**：`GET /api/cs/emergency-events` 从 JWT `csRole` claim（服务端登录时设置，非用户可控）判断，`"ADMIN".equals(csRole)` 时 `EmergencyEventResponse` 附带原始 `gpsLat`/`gpsLng`；普通客服（CS）仍只见 `hasGpsLocation`。同时补充 `status` 查询参数（此前定义未使用），支持按状态筛选历史事件；管理员查看原始坐标时记 `log.info` 审计。
- **涉及文件**：`CsController.getPendingEvents`、`EmergencyEventResponse.from(event, includeRawGps)`、`EmergencyService.getEventsByStatus`
- **验证**：`gradlew compileJava` + `gradlew test` 全量通过；security-reviewer agent 复核 `csRole` 鉴权链路（JWT 签名保证不可伪造）无绕过风险。

### [P2] S17 · 紧急事件原始 GPS 坐标新增 90 天留存清理 — `2026-07-19` 【已确证】

- **背景**：`EmergencyEvent.gpsLat/gpsLng` 此前永久保留，与 `RunOrderTrackPoint` 已有的 90 天留存策略（`docs/轨迹数据留存策略.md`）不一致。
- **方案**：新增 `EmergencyGpsRetentionScheduler`（每日凌晨 3 点，复用 `SchedulerLockService` 分布式锁模式），仅清空超过 `app.emergency.gps-retention-days`（默认 90）天事件的 `gpsLat`/`gpsLng`，**不删除事件行本身**（`status`/`csNotes`/`triggerType` 等仍用于纠纷复核审计，与账号注销级联清理的两方记录保留原则一致）。新增仓储方法 `EmergencyEventRepository.clearGpsBefore()`（`@Modifying @Transactional @Query` UPDATE）。
- **涉及文件**：`scheduler/EmergencyGpsRetentionScheduler.java`（新增）、`EmergencyEventRepository.clearGpsBefore`、`application.properties`（`app.emergency.gps-retention-days=90`）
- **验证**：`gradlew compileJava` + `gradlew test` 全量通过。

### [P0] S1 · Token 黑名单改为按签发时间比对 — `2026-06-17` 【已确证】

- **问题**：登出时黑名单用 `setIfAbsent` 存 `"1"`、按 `userId` 命中。用户登出后重新登录拿到新 token，仍被**旧的、按 userId 命中**的黑名单条目拦截。
- **影响**：用户登出→重登后被锁死，最长 24h 无法使用 App（视旧 token 剩余 TTL）。
- **方案**：黑名单改为存「拉黑时刻时间戳」（覆盖写，`set` 替代 `setIfAbsent`）；`isBlacklisted(userId, issuedAt)` 比对——仅当 token 签发时间 `iat ≤ 拉黑时刻` 才拦截。登出后旧 token 失效，重登的新 token（`iat > 拉黑时刻`）放行。
- **涉及文件**：`TokenBlacklistService`、`JwtFilter`、`JwtHandshakeInterceptor`
- **验证**：`gradlew test` 全量通过（含 `TokenBlacklistServiceTest`）。

### [P0] S2 · REMATCHING 重派次数上限 — `2026-06-17` 【已确证】

- **问题**：志愿者取消订单时转入 `REMATCHING`，`rematchCount++` **无上限**。
- **影响**：订单可无限轮转重派，盲人永远等不到终态反馈。
- **方案**：新增 `MAX_REMATCH_COUNT=3`；志愿者取消时若已达上限，订单转 `CANCELLED` + `CancelledBy.SYSTEM`，通知盲人 `ORDER_AUTO_CANCELLED`，清除 proximity flag。
- **涉及文件**：`OrderLifecycleService.cancelOrder`（志愿者分支）
- **验证**：全量测试通过。

### [P0] A1 · 独立紧急 SOS（orderId 可空） — `2026-06-17` 【已确证】

- **问题**：紧急 SOS 的 `orderId` 强制 `@NotNull`，触发接口先校验订单。
- **影响**：盲人**无活跃订单时无法求救**（接口直接报"订单不存在"）。
- **方案**：`orderId` 改为可选（去 `@NotNull`、`EmergencyEvent.order_id` 列去 `nullable=false`）；`triggerEmergency` 在 `orderId != null` 时才查单+校验参与者；无订单路径直接 `escalateToEmergencyContacts` + 推客服。
- **涉及文件**：`EmergencyTriggerRequest`、`EmergencyEvent`、`EmergencyService.triggerEmergency`
- **部署待办**：生产需手动 `ALTER TABLE emergency_events MODIFY order_id BIGINT NULL;`（`ddl-auto=update` 不改既有列约束）。
- **验证**：`EmergencyServiceTest.triggerEmergency_withoutOrderId_escalatesAndAlertsCs`。

### [P0] A2 · 紧急位置：逆地理编码文字地址 + 三级降级 — `2026-06-17` 【已确证】

- **问题**：紧急短信原本发裸坐标 `31.23,121.47`，家人无法定位；原计划"附地图链接"经查证**违反阿里云 + 运营商规定会发送失败**（2025-05 新规禁短信含链接/IP/联系方式）。
- **影响**：家人收到短信无法快速定位盲人位置。
- **方案**：
  - 新增 `GeocodingService` 接口 + `AmapGeocodingService`（高德 regeo，2s 超时，key 空/异常/`status!=1`/无结果一律降级 `Optional.empty()`）。
  - `formatLocation` 三级降级：无坐标→"位置获取失败…报警110"；有坐标+编码成功→文字地址截断 ≤30 字符；编码失败→"纬度X 经度Y"（5 位小数，<35 字符）。短信**禁放链接**。
- **涉及文件**：`GeocodingService`、`impl/AmapGeocodingService`、`EmergencyService.formatLocation`、`application.properties`
- **配置**：`amap.web.key=${AMAP_WEB_KEY:}`（未配 key **不阻塞上线**，短信走经纬度降级）。
- **后续（第二阶段，未做）**：App/WebSocket 给盲人本人 + 客服后台推可点击高德链接 `https://uri.amap.com/marker?position=lng,lat`（这两个渠道不受短信限制）。
- **验证**：`EmergencyServiceTest`（7 用例）+ `AmapGeocodingServiceTest`（2 用例）+ 全量测试通过。

### [P1] S3 · SecurityConfig HTTP 方法前缀失效 + /cancel 过严 — `2026-06-17` 【已确证】

- **问题（真根因，原评审仅概括）**：SecurityConfig 行99-112 的 `"POST /api/orders/..."` 字符串规则**全部失效**——Spring Security 6 默认用 `MvcRequestMatcher`，**不解析字符串内的 HTTP 方法前缀**，把 `"POST /api/orders/*/accept"` 当字面路径，永不匹配实际请求。所有订单写操作落到兜底规则。此外行101 `POST /api/orders/*/cancel` 配 `hasRole("BLIND")` **过严**——`cancelOrder` 业务上支持志愿者取消（转 REMATCHING），之前被字符串失效掩盖。
- **影响**：真实越权漏洞——BLIND 能进 `/accept` controller、VOLUNTEER 能 `POST /api/orders` 创建订单等；修复中途还暴露 `/cancel` 把志愿者在角色层挡掉。
- **方案**：
  1. 行100-112 全部改用 `requestMatchers(HttpMethod.X, pattern)` 重载（让方法限定真正生效）。
  2. 行101 `/cancel` 改 `hasAnyRole("BLIND", "VOLUNTEER")`（与 `cancelOrder` 双角色分支一致）。
- **涉及文件**：`SecurityConfig`
- **验证**：`OrderPermissionTest`（TC-PERM-04 盲人接单→角色层403）+ `OrderCancelTest`（TC-CANCEL-04/05 志愿者取消）+ 全量测试通过。
- **教训**：原评审把 S3 归因为"行113 通配过宽"是**误判**；实施时测试失败（BLIND 进了 controller）才暴露真正根因是方法前缀字符串失效。

### [P1] S4 · XFF 伪造绕过限流 — `2026-06-17` 【已确证】

- **问题**：`RateLimitInterceptor.extractClientIp` 取 `X-Forwarded-For` 第一个值（`ips[0]`），可被客户端任意伪造。
- **影响**：攻击者在每条请求附带不同伪造 XFF，即可绕过 auth 10/min、registration 20/min、general 60/min 三桶限流（登录爆破、短信轰炸场景尤其严重）。
- **方案（经联网查证 Spring 官方 + adam-p XFF 伪造分析）**：
  - `application.properties` 加 `server.forward-headers-strategy=native`，启用 Tomcat `RemoteIpValve` 处理 XFF。
  - `extractClientIp` 改为直接 `return request.getRemoteAddr()`，删除手动 XFF 解析。单层 Nginx 下默认 `trustedProxies` 为空，取 XFF 最右侧（Nginx 追加的真实客户端 IP），攻击者伪造左侧任意值均无效。
  - 顺手清理 `WebMvcConfig` 无效的 `excludePathPatterns("/api/ws/**")`（真实 WS 端点 `/ws/**` 不在 `/api/**` 下）。
- **涉及文件**：`application.properties`、`RateLimitInterceptor`、`WebMvcConfig`
- **部署待办**：生产 `application.properties`（或 `-D`）同样需加 `server.forward-headers-strategy=native`，否则 `getRemoteAddr()` 返回 Nginx IP（127.0.0.1），**所有限流塌缩到单 IP**。
- **验证**：`RateLimitTest` + 全量测试通过。

### [P1] S5 · 紧急联系人缺失时静默失败 — `2026-06-17` 【已确证】

- **问题**：`EmergencyService.escalateToEmergencyContacts()` 在 `primaryContact == null` 时仅 `log.warn`，盲人无明确反馈——"正在通知家人"的承诺落空，盲人本人无感知。
- **方案**：else 分支增加 `sendNotification(userId, "EMERGENCY_NO_CONTACT", BLIND_USER, null)` 明确反馈盲人；事件推进 `CS_HANDLING` 避免卡在 `VOLUNTEER_NOTIFIED` 被 `TimeoutScheduler` 重复 escalate；`data.sql` 加 `EMERGENCY_NO_CONTACT` 通知模板。
- **涉及文件**：`EmergencyService.escalateToEmergencyContacts`、`data.sql`
- **验证**：`EmergencyServiceTest.escalate_noPrimaryContact_notifiesBlindAndHandsToCs`。

### [P1] B2 · 旧 `/accept` 绕过串行派单协议 — `2026-06-17` 【已确证】

- **问题**：旧 `POST /{id}/accept`、`/reject` 走 `OrderLifecycleService.acceptOrder`，不校验派单归属（`dispatchCurrentVolunteerId`），任何认证志愿者都能抢当前派给别人的单；`rejectOrder` 更是空操作（不推进派单队列）。
- **方案（保留端点 + 复用 /respond 校验，前端零改动）**：
  - `OrderController.acceptOrder/rejectOrder` 改调 `dispatchService.handleVolunteerResponse(id, userId, ACCEPT|DECLINE)`，与 `/respond` 完全同一逻辑（派单归属 + 分布式锁）。
  - `handleVolunteerResponse` 入口加 `registrationStep` + `verified` 资质校验（复用原 `acceptOrder` 检查），未认证志愿者→`OrderPermissionException` 403"请先完成志愿者注册流程"（友好反馈，/accept /respond 一致）。
  - `handleAccept/handleDecline` 的接单失败异常 `IllegalStateException`→`OrderStatusException`（已映射 409，修复接单失败返回 500 的回归；/respond 同受益）。
- **涉及文件**：`OrderController`、`DispatchService`
- **连带测试改造**：`ConcurrencyTest`（重写为串行派单归属测试）、新增 `DispatchServiceTest`（3 用例覆盖归属校验）、`OrderPermissionTest`/`OrderCancelTest` 因 S3/B2 联动自动适配。
- **行为变化（前端须知）**：旧 `/accept` 接单从"直接 IN_PROGRESS"变"PENDING_ACCEPT → 异步 IN_PROGRESS"；响应体仍是 `{success:true, orderId}`，兼容。
- **验证**：`DispatchServiceTest` + `OrderPermissionTest` + `OrderCancelTest` + `ConcurrencyTest`(slow) + 全量测试通过。

### [P1] D1 · MatchingService 事务提交前异步监听导致派单丢失 — `2026-06-19` 【已确证】

- **问题**：`MatchingService.handleOrderCreated` 使用 `@EventListener @Async`，当主线程事务**尚未提交**时 `OrderCreatedEvent` 已发布，异步线程立即执行 `runOrderRepository.findById()`——此时订单在 DB 中还不存在，返回 null，打印"订单 X 未找到，跳过匹配"，**派单流程静默跳过**。
- **影响**：高并发或慢磁盘情形下订单创建后永远不触发派单，盲人等待无响应。生产 E2E 测试中稳定复现。
- **方案**：改为 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) @Async`——事件在主事务**提交后**才触发，异步线程读 DB 时订单必然存在。
- **涉及文件**：`MatchingService.handleOrderCreated`（commit `e77b758`）
- **验证**：生产 E2E 测试（订单 19）：创建→PENDING_MATCH→PENDING_ACCEPT→IN_PROGRESS 全流程通过。

### [P1] D2 · OrderLifecycleService `@TransactionalEventListener` + `@Transactional` 启动崩溃 — `2026-06-19` 【已确证】

- **问题**：`OrderLifecycleService.onDispatchAccepted` 使用 `@EventListener @Async @Transactional`——同 D1 根因（事务前触发），接单时查到的订单状态仍是 PENDING_MATCH 而非 PENDING_ACCEPT，报"期望 PENDING_ACCEPT"。修复 D1 思路直接改 `@TransactionalEventListener(AFTER_COMMIT) @Async @Transactional` 触发 **Spring 启动崩溃**："must not be annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED"（Spring 限制：`@TransactionalEventListener` 默认在父事务已提交后执行，默认传播 `REQUIRED` 会加入已不存在的事务）。
- **方案**：加 `@Transactional(propagation = Propagation.REQUIRES_NEW)`——在新事务中执行，绕过 Spring 限制，同时保证 `onDispatchAccepted` 的 DB 写操作具有事务保护。
- **涉及文件**：`OrderLifecycleService.onDispatchAccepted`（commit `d0cf543`）
- **验证**：服务正常启动（无崩溃）+ 生产 E2E 测试接单后状态 IN_PROGRESS 正确。

### [P1] E1 · EmergencyService `notifyContact`/`resolveEvent` SMS 异常未捕获导致 500 — `2026-06-20` 【已确证】

- **问题**：`notifyContact()` 和 `resolveEvent()` 直接调用 `notificationService.sendEmergencyAlertSms()` / `sendEmergencyResolvedSms()`，无 try-catch。阿里云 SMS 在测试/生产环境中可能因配置缺失或网络超时抛出异常，该异常由方法上的 `@Transactional` 捕获后触发回滚，返回 HTTP 500，且 DB 状态变更也被回滚。同一方法内的 `escalateToEmergencyContacts` 已在 commit `a88b699` 中修复，两处遗漏。
- **影响**：CS 调用「通知联系人」和「标记解决」接口时，SMS 失败直接导致整个接口 500，DB 状态变更（`CONTACT_NOTIFIED` / `RESOLVED`）也同时回滚，造成事件状态错误卡住。
- **方案**：对两处 SMS 调用各加 try-catch，SMS 失败仅记 `log.error` 告警，不抛出，DB 事务正常提交。复用 `escalateToEmergencyContacts` 中已有的修复模式。
- **涉及文件**：`EmergencyService.notifyContact`（`sendEmergencyAlertSms` 调用处）、`EmergencyService.resolveEvent`（`sendEmergencyResolvedSms` 调用处）
- **验证**：生产测试 `PUT /api/cs/emergency-events/7/resolve` → HTTP 200 `{success:true}`（修复前 500）。

### [P1] B1 · GlobalExceptionHandler 把所有 `IllegalStateException` 误映射到 429 — `2026-06-20` 【已确证】

- **问题**：commit `cd6d8b7` 修"紧急冷却返回 429"时把 `handleIllegalState` 整个映射到 `HttpStatus.TOO_MANY_REQUESTS`，同时直接把 `e.getMessage()` 返回给客户端。8+ 处 `IllegalStateException` 均受影响：志愿者响应状态（本应 409）、缺失联系人（本应 400）、订单状态不支持（本应 409）等，全部返回 429 并泄露内部错误文本。
- **影响**：客户端收到错误的 HTTP 状态码；内部业务逻辑文本（如"当前订单状态不允许志愿者响应"）直接暴露给前端，信息泄露。
- **方案（两步修复）**：
  1. `EmergencyService.triggerEmergency` 冷却检测改用 `RateLimitException(cooldownSeconds)` 替代 `IllegalStateException`（`RateLimitException` 已有正确 429 handler + `Retry-After` header）。
  2. `GlobalExceptionHandler.handleIllegalState` 改为返回 HTTP 500 + 通用消息"服务器内部错误，请稍后重试"（不再暴露 `e.getMessage()`）；同时补 `log.error` 记录实际异常信息供排查。
- **涉及文件**：`EmergencyService`（line 79）、`GlobalExceptionHandler`（`handleIllegalState`）、`EmergencyServiceTest`（期望异常类型改 `RateLimitException`）
- **验证**：紧急冷却 → HTTP 429 + `retryAfterSeconds`（正确）；其余 `IllegalStateException` → HTTP 500 + 通用消息（不再 429）；全量测试通过。

### [P1] C1 · SecurityConfig 缺少 `POST /api/orders/*/call/initiate` 显式授权规则 — `2026-06-20` 【已确证】

- **问题**：`POST /api/orders/*/call/initiate` 未在 SecurityConfig 中显式授权，落入 `anyRequest().authenticated()`，意味着包括 CS 客服在内的任何已认证用户均可调用。
- **影响**：CS 用户（`CS_CS`/`CS_ADMIN`）可发起通话，违背"仅订单参与方（盲人/志愿者）才能发起"的业务约束。CallController 本身有参与者校验（403），但安全防御应在 Security 层最早期拦截。
- **方案**：在 SecurityConfig 的 `call/initiate` 前加 `.requestMatchers(HttpMethod.POST, "/api/orders/*/call/initiate").hasAnyRole("BLIND", "VOLUNTEER")`（与 S3 历史教训一致，必须用 `HttpMethod` 重载）。
- **涉及文件**：`SecurityConfig`
- **验证**：编译通过；全量测试通过。

### [P1] P1 · 多实例 WebSocket 通知（更正：已解决） — `2026-06-17` 【已确证】

- **原描述（不准确）**：`UnifiedSessionRegistry` 是本地内存，多实例通知丢失。
- **核实结论**：代码库**已实现完整的 Redis Pub/Sub 跨实例转发**——`UnifiedSessionRegistry.sendToUser` 本机无 session 时转发 Redis、`WebSocketMessageBroker` 的 `ws:messages` 频道桥接 USER/CS_BROADCAST、`RedisWebSocketConfig` 全实例订阅。生产当前单实例未承压，但跨实例机制已预先就绪。
- **处理**：无需改动代码，仅更正背景描述并标记已解决。

### [P2] C2 · `application.properties` 缺少订单匹配超时配置文档 — `2026-06-20` 【已确证】

- **问题**：`app.match.timeout-seconds`（`OrderCreationService:35`）和 `app.rematch.timeout-seconds`（`OrderLifecycleService:45-48`）在代码中使用默认值 300s，但 `application.properties` 未显式列出这两个 key，运维无从知晓可以调整。
- **方案**：在 `application.properties` 的"串行派单配置"区块下补充说明，明确两个超时 key 及其默认值。
- **涉及文件**：`application.properties`（dispatch 配置区块）

### [P2] T1 · 冒烟测试覆盖缺口 — 全部补测完成 — `2026-06-20` 【已确证】

以下 6 个接口已于 2026-06-20 在生产服务器完成补测，全部返回 2xx：

| 接口 | 状态 | 返回示例 |
|------|------|---------|
| `PUT /api/orders/{id}/keep-waiting` | ✅ 200 | `{success:true}` |
| `POST /api/orders/{id}/call/initiate` | ✅ 200 | `{callRecordId:2, virtualNumber:"170...", status:"CONNECTED"}` |
| `GET /api/orders/{id}/call/records` | ✅ 200 | `[{...}]`（1条记录） |
| `GET /api/blind/volunteer-location` | ✅ 200 | `{lat:22.5431, lng:114.0579, orderId:29}` |
| `PUT /api/cs/emergency-events/{id}/notify-contact` | ✅ 200 | `{success:true}` |
| `PUT /api/cs/emergency-events/{id}/false-alarm` | ✅ 200 | `{success:true}` |

**测试前提**：`sms.test-phones=15602964366,13823594196`（000000 固定码）已在生产服务配置；志愿者 userId=10 通过数据库直接设置 `verified=1, registration_step=STEP_4_COMPLETED`。

### [P2] V1 · 超时统计与主动拒绝分开 — `2026-06-19` 【已确证】

- **核实**：`handleVolunteerTimeout` 调 `updateDeclineStats`，与主动拒绝走同一 SQL，超时 `total_declined+1` 拉低 `acceptanceRate = accepted/(accepted+declined)`。原 ISSUES 写"权重×10"不准（实际 `WEIGHT_ACCEPTANCE=5.0`，配速才是 10，Javadoc 也写反）。
- **方案（方案A）**：`VolunteerProfile` 加 `totalTimeout` 字段；Repository 加 `atomicIncrementTimeoutStats`（递增 total_dispatched + total_timeout，不动 total_declined/accepted）；`handleVolunteerTimeout` 改调它。超时对 acceptanceRate 完全中性（不计分子分母），且保留超时数据供分析。
- **理由**：主动拒绝是主观意愿，超时是客观未响应（网络/没看到），混为一谈对志愿者不公；单独统计保留数据。
- **涉及**：`VolunteerProfile`、`VolunteerProfileRepository`、`DispatchService.handleVolunteerTimeout`、`ScoringService` Javadoc 更正。

### [P1] V2 · 志愿者 Step1 二要素失败仍推进到 Step3，导致 Step3 init 报"身份信息格式不正确" — `2026-07-08` 【已确证】

- **问题**：`VolunteerRegistrationService.submitBasicInfo` 调用阿里云 Id2Meta 二要素核验后，**无论通过与否都执行 `setRegistrationStep(STEP_3_FACE_VERIFY)`**（旧代码注释明写"失败仍推进到 step3"）。前端因此进入"活体认证"页，点击 init 时后端把 Step1 存的 `idCardName/idCardNumber` 传给阿里云 `InitFaceVerify`，阿里云因身份信息不合法返回 code 401，后端翻译成"身份信息格式不正确"。**Step1 推进成功 ≠ 二要素通过**，前后端状态矛盾。
- **影响**：身份证二要素本就没通过（或前端传了带空格/末尾小写 x 等本地正则放行但阿里云拒的格式）的用户，被错误推进到活体页，在 init 阶段才失败，体验割裂，且无结构化错误码可供前端引导。
- **次要根因**：Step1 落库**未做 trim/大写归一化**——`BasicInfoRequest` 正则 `^\d{17}[\dXx]$` 放行小写 x 和带空格输入，阿里云二要素与人脸初始化对此敏感。
- **方案**：
  1. `submitBasicInfo` 落库前对身份证号 `.trim().toUpperCase()`、姓名 `.trim()`。
  2. 二要素核验**失败即拦截**：保持 `STEP_1_BASIC_INFO` + 落库 `REJECTED`，抛 `RegistrationRejectedException(ErrorCode.ID_INFO_INVALID)`（HTTP 400）。
  3. `initFaceVerify` 防御兜底：发现 `idVerifyStatus=REJECTED` 的历史脏数据时**回退步骤到 STEP_1_BASIC_INFO** 并抛 `ID_INFO_INVALID`，引导用户重填（兼容本次修复前已卡在 step3 的用户）。
  4. 新增错误码 `ErrorCode.ID_INFO_INVALID`（400）+ 异常类 `RegistrationRejectedException`（带 errorCode，区别于流程顺序异常 `RegistrationStepException` 的 409）。
  5. `GlobalExceptionHandler` 注册新异常 → 统一 `{success:false, code:400, errorCode:"ID_INFO_INVALID", message}`。
- **字段决策（前端契约）**：`name`（展示名）与 `idCardName`（身份证姓名）**保留两个字段**——二要素与人脸核验只用 `idCardName/idCardNumber`，`name` 仅用于通知/展示。前端填一次姓名、提交时 `name=idCardName` 即可。
- **涉及文件**：`VolunteerRegistrationService`、`ErrorCode`、`RegistrationRejectedException`（新增）、`GlobalExceptionHandler`、`VolunteerRegistrationServiceTest`
- **前端须知**：① Step1 收到 `ID_INFO_INVALID` → 停在 Step1 提示核对；② Step3 init 收到 `ID_INFO_INVALID` → 调 `/status` 刷新，步骤已回退到 `STEP_1_BASIC_INFO`。
- **验证**：`VolunteerRegistrationServiceTest` 全部通过（2 个断言对齐新行为 + 1 个新增 trim/大写归一化用例）。

### [P0] V3 · InitFaceVerify `ProductCode` 无效值导致动作活体认证 100% 返回 401 — `2026-07-10` 【已确证】

- **问题**：`AliyunIdVerifyService.initFaceVerify` 中 `InitFaceVerifyRequest.setProductCode("SMART")`——查证阿里云官方 `InitFaceVerify` 接口文档（页面标题"InitFaceVerify-发起认证请求"）确认 `ProductCode` **唯一合法取值是 `LR_FR`**，`"SMART"` 从未是有效值。真正控制活体检测类型（眨眼/点头/静默等）的是 `Model` 字段（默认 `LIVENESS`，我们代码未显式设置，走默认即可），与 `ProductCode` 无关——命名相近导致此前排查方向被误导。
- **影响**：志愿者 Step3 动作活体认证**全量 100% 失败**，每次 `init` 调用均返回 `code=401, message=参数非法`，志愿者注册流程在 Step3 彻底卡死，无法进入 STEP_4_COMPLETED、无法接单。此前多轮针对 `sceneId`/`metaInfo`/身份信息格式的排查方向均未命中根因（详见 V2，V2 修复的是另一个真实但非本次根因的问题）。
- **方案**：`ProductCode` 由 `"SMART"` 改为 `"LR_FR"`；同步更正类注释。
- **涉及文件**：`AliyunIdVerifyService.initFaceVerify`（`ProductCode` 字段 + 类级 Javadoc）
- **部署**：已通过 `deploy.sh` 部署到生产（备份 `demo-0.0.1-SNAPSHOT.jar.bak.20260709_224747`，健康检查通过）。
- **验证**：生产 E2E 复测——用白名单测试号 `15602964366`（固定验证码 `000000`）注销旧账号（userId=9）→ 重新注册为志愿者（userId=20）→ Step1 实名核验通过 → Step3 `face-verify/init` 报错从修复前的 `401 参数非法` 变为 `400 参数不能为空(userId)`，证明 `ProductCode` 校验层已通过，根因确认修复。
- **⚠️ 更正（见 V4）**：本条最初结论"剩余 `400 参数不能为空(userId)` 是 metaInfo 需真机 SDK 采集、curl 无法伪造的接口设计边界"**已被证伪**——真机账号（userId=19，真实 Aliyun SDK 采集的 metaInfo）复现了完全相同的错误，说明这是代码缺陷而非测试边界，根因见 V4。

### [P0] V4 · InitFaceVerify 遗漏必填 `UserId` 字段导致动作活体 100% 返回 400 — `2026-07-10` 【已确证】

- **问题**：`AliyunIdVerifyService.initFaceVerify` 构建 `InitFaceVerifyRequest` 时从未调用 `.setUserId(...)`。经提取阿里云 SDK 源码包（`cloudauth20190307-2.1.1-sources.jar`）确认 `InitFaceVerifyRequest` 存在 `UserId`（String）字段且为必填——`ProductCode=LR_FR` 场景下阿里云服务端强校验此字段，未传即报 `400 参数不能为空(userId)`。此前误判为"curl 伪造 metaInfo 触发的预期边界"（见 V3 更正），实为真实代码缺陷：真机账号 userId=19（用户 iOS 端上报，真实 SDK 采集的合法 metaInfo）与本地测试账号 userId=20 均 100% 复现同一报错，与 metaInfo 真实性无关，与账号身份/JWT 映射无关。
- **影响**：志愿者 Step3 动作活体认证在 ProductCode 修复（V3）后依然 **100% 失败**，`init` 恒定返回 `400 参数不能为空(userId)`，注册流程卡死在 Step3，无法进入 STEP_4_COMPLETED、无法接单。
- **方案**：服务端本就在 `VolunteerRegistrationService.initFaceVerify(Long userId, ...)` 持有从 JWT 解析出的 `userId`（controller 层 `SecurityUtils.getCurrentUserId()` 传入），只需将其透传到 SDK 请求即可——**不新增前端契约字段，不改 OpenAPI**：
  - `FaceVerifyService.initFaceVerify(...)` 接口签名新增 `String userId` 首参
  - `AliyunIdVerifyService.initFaceVerify` 内 `InitFaceVerifyRequest` 新增 `.setUserId(userId)`
  - `TestFaceVerifyServiceImpl`（测试桩）签名同步更新（忽略该参数）
  - 调用方 `VolunteerRegistrationService.initFaceVerify` 传入 `userId.toString()`
- **涉及文件**：`FaceVerifyService.java`、`AliyunIdVerifyService.java`、`TestFaceVerifyServiceImpl.java`、`VolunteerRegistrationService.java`、`TestFaceVerifyServiceImplTest.java`、`VolunteerRegistrationServiceTest.java`（测试签名同步）
- **部署**：已通过 `deploy.sh` 部署到生产（备份 `demo-0.0.1-SNAPSHOT.jar.bak.20260710_112900`，健康检查通过）。
- **验证**：`gradlew compileJava` 通过；`gradlew test` 全量 177 项通过（含 `TestFaceVerifyServiceImplTest`、`VolunteerRegistrationServiceTest`）。生产 E2E 复测——用户 userId=20 + 伪造 metaInfo 调用 Step3 init，服务端日志确认阿里云返回 `code=200, message=success`（此前是 `400 参数不能为空(userId)`），仅因 metaInfo 非真机 SDK 采集导致 `ResultObject` 缺失（`认证服务返回不完整`）——证明 `UserId` 校验层已通过，根因确认修复；剩余的 `ResultObject` 缺失需真机 H5Face/RPWeb SDK 采集的合法 metaInfo 才能验证，待前端联调最终确认 userId=19/真机场景。
  - **⚠️ 更正（见 V5）**：此处判断有误，缺失的不是真机 metaInfo，而是后端将 `certifyUrl` 错误地当作必填字段——详见 V5。
- **教训**：SDK 报错文案本身就是最权威的一手证据（`参数不能为空(userId)` 已明确点名字段），不应用"测试数据不够真实"这类假设去解释一个明确指名道姓的服务端校验错误；阿里云官方文档页面是 JS 渲染 SPA，`fetch`/`WebFetch` 类工具抓不到正文，直接提取 Maven 缓存里的 SDK sources jar 读源码比啃文档更快更准确。

### [P0] V5 · InitFaceVerify 误将 `certifyUrl` 当必填字段，App SDK 场景本无该值 — `2026-07-10` 【已确证，待生产验证】

- **问题**：V4 修复 `UserId` 后，生产 E2E 复测出现新错误 `认证服务返回不完整`（InitFaceVerify `ResultObject` 缺失）。V4 收尾时误判为"metaInfo 非真机 SDK 采集所致"，该假设已被证伪。经 iOS 开发详细反馈核实：本项目 iOS 端使用阿里云**原生 App SDK 流程**（`AliyunFaceAuthFacade` 2.3.48），真实 InitFaceVerify 响应仅含 `Code`/`Message`/`ResultObject.CertifyId`——`CertifyUrl` **只在 H5/Web 集成场景才返回**，本项目并未使用该场景。而后端 `AliyunIdVerifyService.parseInitResult()` 却要求响应中 `certifyId` **和** `certifyUrl` 同时非空才判定成功，导致每一次真实 App SDK 响应都被错误地当作"不完整"而拒绝——错误原因正是响应正确地缺少了一个本就不该有的字段。
- **影响**：志愿者 Step3 动作活体认证在 V4（UserId）修复后依然 **100% 失败**（表现为 `认证服务返回不完整`），注册流程卡死在 Step3，无法进入 STEP_4_COMPLETED、无法接单，与 V4 是同一功能点上连续第二次被后端逻辑本身挡住。
- **方案**：`AliyunIdVerifyService.parseInitResult()` 成功判定条件由 `resultObject.getCertifyId() != null && resultObject.getCertifyUrl() != null` 改为仅要求 `resultObject.getCertifyId() != null`。同步更新 `AliyunIdVerifyService.java`、`FaceVerifyService.java`（接口 Javadoc）、`VolunteerRegistrationService.java`（类/方法注释）说明这是 App SDK 流程而非 H5/Web，响应中 `certifyUrl` 将正常为 `null`——客户端（iOS）直接用 `certifyId` 调 `AliyunFaceAuthFacade.verify(certifyId)`，不需要 URL。不改客户端请求契约（init 入参仍只是 `metaInfo`）；响应 DTO 结构不变（仍保留 `certifyUrl` 字段以兼容旧 JSON），但 App SDK 流程下会恒为 `null`。
- **涉及文件**：`AliyunIdVerifyService.java`（`parseInitResult` 逻辑 + 注释）、`FaceVerifyService.java`（接口 Javadoc）、`VolunteerRegistrationService.java`（注释，无逻辑变化——该类本就只是透传 certifyId/certifyUrl/status/message）、`docs/api_spec.yaml`（`FaceVerifyInitResponse` schema：`certifyUrl`/`certifyId` 改为可空、`required` 数组仅保留 `status`；init/result 端点描述改为 App SDK 流程说明，不再提"打开 certifyUrl"）、`docs/frontend-guide.md`（同步 App SDK 流程更正，3 处）。
- **验证**：`gradlew compileJava` 通过；`gradlew test` 全量 177/177 通过（`AliyunIdVerifyService` 原无用例断言 certifyUrl 必填行为，无需改动；`TestFaceVerifyServiceImplTest` 用的是无关的 `TestFaceVerifyServiceImpl` 测试桩，仍返回 URL，因调用方已不再要求该字段而不受影响）。**尚未部署到生产，也未经真机验证**——下一步待办，非已完成项。
- **教训**：这是同一功能点连续第二次被修正（V3 被 V4 修正；如今 V4 收尾时"预期能拿到 certifyUrl，待真机验证"的假设本身又被 V5 修正）——当错误是从局部/伪造测试数据（虚构 metaInfo、非真机）推断出来时，不能想当然地认为剩余差距"只是测试数据不够真实"，而应先核实**成功判定标准本身**是否符合实际集成契约（App SDK 与 H5/Web 的响应结构不同）。直接找到有真实接口响应日志的客户端开发者当面核实，比单纯从合成测试的报错信息去猜测根因更可靠。

### [P1] V6 · 去除接单前置的注册/培训门槛（产品决策：先上线再说） — `2026-07-16` 【已确证】

- **背景**：志愿者人脸活体认证（Step3）通过后仍无法接单——根因是接单需 `registrationStep == STEP_4_COMPLETED`，而活体通过只推进到 `STEP_4_TRAINING`，还需完成 3 门培训课程 + 测验才能自动转 `STEP_4_COMPLETED` 并 `verified=true`（见 `TrainingService.checkAllCoursesCompleted`）。经与产品讨论培训门槛的必要性，**决策为完全去除该门槛，先上线，后续视线上情况再决定是否恢复/替换为轻量审核**。
- **改动**：删除三处独立的 `registrationStep != STEP_4_COMPLETED` / `!verified` 强制校验（原本三处各自把关，缺一不可，需一并删除才真正生效）：
  1. `DispatchService.handleVolunteerResponse`（生产实际接单入口，`/respond` 与旧 `/accept`、`/reject` 共用）。
  2. `ScoringService` 派单候选硬性过滤（`populateQueue` 用，未完成培训的志愿者此前根本进不了候选池，光删第 1 处不够）。
  3. `OrderLifecycleService.acceptOrder()`（生产无调用点，仅单测覆盖，一并同步保持一致）。
- **未删除**：`RegistrationStep`/`verified` 字段、培训课程与测验体系（课程内容、测验、防作弊）全部保留，只是不再强制阻断接单；`getRegistrationStatus` 的 `canAcceptOrders` 展示字段未改。
- **测试改造**：`ScoringServiceTest` 删除"硬性过滤：注册未完成被排除"用例；`OrderPermissionTest` 删除 TC-PERM-05（未认证志愿者接单需 403）——该场景现在走正常的派单归属校验（未派送给您 → 409），不再有单独的认证 403 分支。
- **涉及文件**：`DispatchService.java`、`ScoringService.java`、`OrderLifecycleService.java`、`ScoringServiceTest.java`、`OrderPermissionTest.java`
- **验证**：`gradlew compileJava`/`compileTestJava` 通过；`service` 包全量单测通过（40 用例，含 ScoringServiceTest/OrderServiceTest/DispatchServiceTest）；`OrderPermissionTest` 因本机无 Docker 导致 Testcontainers 初始化失败，与本次改动无关（非回归）。
- **⚠️ 待办（未完成）**：尚未部署到生产 `47.114.113.171`；无数据库迁移需求（未改字段/表结构）。

### [P0] S11 · 登出误撤销同账号其他有效 Token（选角色重发 Token 场景） — `2026-07-13` 【已确证】

- **问题**：云端契约验证复现（测试用户 23）：手机验证码登录拿 Token A → `POST /api/user/role` 选角色拿替换 Token B（两者均曾有效）→ 用 Token A 调 `POST /api/auth/logout` → Token B 也随之失效。根因是 S1（2026-06-17）修复引入的黑名单机制按**用户整体**撤销：`jwt:blacklist:{userId}` 只存一个"拉黑时刻"时间戳，任何 `iat ≤ 该时刻` 的 token 全部失效——Token B 的签发时间必然早于登出调用时刻，因此被一并误杀。这不是随机边缘场景，而是本项目"选角色返回新 Token，客户端须替换本地存储"这一正常流程天然会触发的路径。
- **影响**：客户端只要有一处仍持有登出前签发的旧 token（缓存、时序竞态等）去调登出，就会把当前正在使用的新 token 一并撤销，用户被迫重新登录；且账号自助清理（本人登出后自助注销）在此路径下会被连锁锁死到自身 token 也失效。
- **方案**：登出改为**按单个 token 撤销**（新增 JWT `jti` claim + Redis key `jwt:blacklist:jti:{jti}`，TTL=该 token 剩余有效期），不再影响同账号其他 token；账号注销/管理员封禁场景保留原有按用户整体撤销的机制（`blacklistUserWithMaxTtl`，语义上就需要撤销全部 token，未改动）。升级前签发、无 jti 的旧 token 无法单独定位，退化为按用户整体撤销（影响随 TTL 到期自然收敛）。`JwtFilter`/`JwtHandshakeInterceptor` 同时检查按 jti 撤销和按用户整体撤销两种情况。**顺带修复**两处独立缺口：`UserService.deleteAccount()` 活动订单拦截的 errorCode 从通用的 `ORDER_STATUS_NOT_ALLOWED` 改为专用的 `ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED`（新增于 `ErrorCode` 枚举，HTTP 409）；`DELETE /api/users/{id}` 成功响应补充 `phoneReusable: true`、`allTokensInvalidated: true` 字段。
- **涉及文件**：`JwtUtil`（`jti` claim）、`TokenBlacklistService`（新增 `blacklistToken`/`isTokenBlacklisted`，移除 `blacklistUserFromToken`）、`JwtFilter`、`JwtHandshakeInterceptor`、`AuthController`、`CsAuthController`（登出改调 `blacklistToken`）、`ErrorCode`（新增 `ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED`）、`UserService.deleteAccount`、`UserController.deleteUser`、`TokenBlacklistServiceTest`
- **测试用户 23 处理**：无需人工/管理员数据库介入。当前失效的是"登出前已签发"的旧 token；只要用原手机号重新走验证码登录拿一个全新 token（签发时间晚于旧黑名单时刻），即可正常调用 `GET /api/auth/me` 与 `DELETE /api/users/23` 自助完成软删除。
- **⚠️ 待办（未完成）**：本次为本地代码修复+单元测试验证，**尚未部署到 `47.114.113.171` 生产环境**。部署后需按用户提供的检查清单复测：①删除他人 ID → 403（已有逻辑，未改动）；②有活动订单删除 → 409 + `ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED`；③软删除成功；④删除后该账户全部 token → 401（`blacklistUserWithMaxTtl` 未改动，应仍生效）；⑤原手机号可重新注册；⑥删除响应含 `success/phoneReusable/allTokensInvalidated` 三字段。同时需同步 `docs/api_spec.yaml`（logout 契约说明「仅撤销当前 token」+ 补充删除响应字段）、`docs/frontend-guide.md`、`docs/test-accounts.md`，并通知 iOS 端登出行为契约已确认为"仅当前 token"（与其原假设一致，无需改客户端）。
- **验证**：`gradlew compileJava`/`compileTestJava` 通过；`TokenBlacklistServiceTest`（含新增用例，覆盖"登出 A 不影响 B"场景）通过；`gradlew test` 121 项非 Docker 依赖用例通过（13 项 Testcontainers 集成测试因本地无 Docker daemon 未跑，非本次改动引入的回归）。

### [P0] S12 · `.claude/settings.json` 明文泄露阿里云 AccessKey + CS 管理员密码哈希 — `2026-07-16` 【已确证】

- **触发**：阿里云风控系统告警检测到账号存在泄露的 AccessKey（GitHub 密钥扫描合作伙伴计划扫描公开仓库并通知云厂商——本仓库 `Jayden23018/blind-run-backend` 为 **public**）。
- **问题**：`.claude/settings.json`（Claude Code 权限白名单文件）里的 `permissions.allow` 记录了历史上用户实际敲过的、**带内联凭证的完整 shell 命令**，随该文件一起被提交进 git 并推送到公开仓库。经排查共两组可用凭证 + 一组密码哈希：
  - AccessKey ID `LTAI5tQ4****`（已作废）+ 配对 Secret（已作废，不在此记录明文）
  - AccessKey ID `LTAI5t7T****`（已作废）+ 配对 Secret（已作废，不在此记录明文）
  - CS 管理员（`cs_users.admin`）密码 bcrypt 哈希 ×2（历史两次手动改密留下的记录）
  - 引入 commit：`9e91473`（2026-07-07，"新增志愿者人脸认证功能"）。这是此前 `.gitignore` 名单（`gradle.properties`、`start-with-correct-env.sh`）**未覆盖的新泄露载体**——根因是把带内联环境变量的一次性调试命令记进了权限白名单，而非专门的密钥文件。
  - **影响**：泄露凭证可直接调用阿里云 API（短信/人脸核身/OSS 等本项目已开通的服务）；管理员密码哈希存在离线暴力破解风险。
- **方案**：
  1. 清空当前工作区 `.claude/settings.json` 中的明文 AccessKey/Secret/密码哈希 5 处权限条目（不影响其余正常白名单项），提交新 commit（可逆、不改历史）。
  2. **用户已在阿里云 RAM 控制台自行禁用/轮换涉事 AccessKey**，并重置 CS 管理员密码。
  3. **暂不清洗 git 历史**（`git filter-repo` + force-push）——决策：旧凭证既已作废，历史清洗收益（防止后来者看到已失效的字符串）相对破坏性操作（改写公开仓库全部历史 hash）不划算，先只轮换密钥。如后续需要，仍可执行。
- **涉及文件**：`.claude/settings.json`
- **⚠️ 待办（未完成）**：
  - 团队约定：本地调试**不要**把凭证以内联环境变量形式直接写进会被记录的 shell 命令（`export`/`FOO=bar cmd` 均会被 Claude Code 权限系统原样记录）——本项目已有 `start-with-correct-env.sh`（已 gitignore）承载该用途，应始终通过它加载凭证，不要在命令行现敲。
  - 建议定期 `grep -rn "LTAI\|AKIA\|\\$2[ab]\\$"` 检查 `.claude/settings.json` 等非常规文件，纳入定期审计。
- **验证**：清理后 `grep -n "LTAI\|2a\$10\$" .claude/settings.json` 无匹配。

### [P2] A8 · 派单进度反馈 + 修复模板缺失 bug — `2026-06-19` 【已确证】

- **核实**：派单期间盲人零逐志愿者进度；且 `DISPATCH_EXPANDING`（扩圈）、`ORDER_AUTO_CANCELLED`（自动取消）三个触发点都在发通知，但 **data.sql 无模板 → 静默失效**（订单被系统取消盲人收不到通知，真 bug）。
- **方案**：
  - ① 补缺失模板（修 bug）：`DISPATCH_EXPANDING` + `ORDER_AUTO_CANCELLED` → 立即生效。
  - ② 加 `DISPATCH_STARTED`（首次派单正向反馈，参考滴滴"正在为您呼叫"节奏：首次 + 扩圈，不每志愿者打扰）。
- **理由**：补模板是修 bug（必须）；进度反馈参考滴滴等待期正向信号设计（告知"正在呼叫"而非"还没找到"），避免 30s/次的噪音打扰。
- **涉及**：`data.sql`（3 条新模板）、`DispatchService.initiateDispatch`（发 DISPATCH_STARTED）。

### [P2] A3 · TTS 紧急文案按无障碍原则修订 — `2026-06-19` 【已确证】

- **核实**：ttsText 随 WS 消息下发用于语音播报；紧急文案铺垫冗长（`EMERGENCY_NO_CONTACT` 37 字）、"点击通话按钮"对全盲用户是视觉词、紧急等待期无时效预期。
- **方案**：按紧急语音最佳实践（注意-说事-动作-简短，参考 Baldwin Boxall/Hall of States 疏散广播原则 + WCAG）修订紧急 ttsText：前置关键动作、去视觉词、加后续指引。
- **理由**：紧急场景盲人需 1-2 秒抓核心，冗长铺垫延迟关键信息；视障用户依赖听觉，文案应口语化、避免视觉词。
- **涉及**：`data.sql`（3 条紧急模板 tts_text）、`NotificationService`（2 处硬编码 tts）。
- **后续**：文案最终措辞建议与视障用户/无障碍专家 1 轮共创定稿。

### [P2] S6 · Swagger 生产关闭 — `2026-06-19` 【已确证】

- **核实更正**：ISSUES 原标"待处理"，实际 `application-prod.properties:36-37` 已 `springdoc.swagger-ui.enabled=false` + `springdoc.api-docs.enabled=false`——生产 profile 下 Swagger 已关闭。文档滞后，本次更正状态。
- **涉及**：`application-prod.properties`（无需改动，已就绪）。

### [P2] S9 · 生产 ddl-auto 改 validate — `2026-06-19` 【已确证】

- **问题**：原 `application.properties` `${JPA_DDL_AUTO:update}`，prod profile 未覆盖 → 生产默认 update，表结构漂移风险。
- **方案**：`application-prod.properties` 加 `spring.jpa.hibernate.ddl-auto=validate`（生产 profile 覆盖）。实体与表结构不匹配时启动失败（暴露问题而非静默改表）。
- **理由**：生产不应由 Hibernate 自动改表（不可控、可能丢数据/改错）；validate 只校验不修改，安全。新增字段（如 V1 `total_timeout`）生产首次部署需确认列已存在（临时用 update 启动一次，或手动 ALTER）。
- **涉及**：`application-prod.properties`、`CLAUDE.md`（ddl-auto 说明更正）。

### [P0] S10 · 生产部署遗漏 FE-1 迁移导致 crash-loop + 全站 502 — `2026-06-23` 【已确证】

- **问题**：v1.5.0 部署到生产时，FE-1 要求的 `ALTER TABLE volunteer_profile ADD COLUMN wants_dispatch` 迁移**未执行**（仅写在 CLAUDE.md 提示和 ISSUES FE-1 条目里，未进任何部署脚本/checklist）。生产 `ddl-auto=validate`（S9），Hibernate 启动校验发现 `VolunteerProfile.wants_dispatch` 实体字段在库中无对应列 → 抛 `SchemaManagementException: missing column [wants_dispatch] in table [volunteer_profile]` → 进程 exit 1。
- **影响**：应用无法启动，systemd 触发**自动重启死循环**（日志显示 `restart counter is at 51`）。8081 端口无进程监听，Nginx 代理返回 **502 Bad Gateway**，公网 IP `http://47.114.113.171` 与 `:8081` 均不可访问。**迷惑点**：`systemctl status` 在 crash-loop 间隙显示 `active (running)`，造成"服务正常"的假象。
- **方案**：在生产 MySQL 执行 FE-1 迁移 SQL：
  ```sql
  ALTER TABLE volunteer_profile ADD COLUMN wants_dispatch BOOLEAN NOT NULL DEFAULT TRUE;
  ```
  执行后 systemd 下次自动重启即成功：日志出现 `Tomcat started on port 8081` + `Started DemoApplication in 15.3s`；8081 直连 HTTP 200，Nginx 80 代理 HTTP 200，服务全量恢复。
- **涉及**：生产数据库 `blind_running.volunteer_profile`（仅 DDL，无代码改动）。
- **根因（流程缺陷）**：迁移 SQL 只存在于文档，没有进 `deploy.sh` 或独立 migration 脚本，部署时靠人工记忆，必然遗漏。
- **教训与改进**：
  1. **所有 prod `ALTER TABLE` 迁移必须固化进部署脚本**（`deploy.sh` 或独立 `db/migration/*.sql`），部署前自动幂等执行，不依赖人工手动跑 + CLAUDE.md 提示。
  2. **systemd 防 crash-loop 伪装 running**：给 `blindrun.service` 加 `StartLimitIntervalSec=` / `StartLimitBurst=`（如 60s 内重启超 5 次进 failed 状态），避免 crash-loop 间歇性 running 掩盖"服务其实已死"。部署后用 `curl http://127.0.0.1:8081/` 验证返回非 502/000，不能只看 `systemctl status`。
  3. **部署后健康检查**：deploy 流程末尾自动 `curl` 健康端点，失败即回滚/告警。

---

### [P1] SMS-A3 · EMERGENCY_ALERT 短信模板参数格式被阿里云拒绝 — `2026-06-20` 【已确证，2026-07-18 已解决】

- **问题**：`EmergencyService.sendEmergencyAlertSms` 发送紧急短信时，阿里云 EMERGENCY_ALERT 模板拒绝 `location`（坐标降级格式，模板变量类型为 `[地址]`）和 `time`（`LocalDateTime.toString()` 含纳秒，模板变量类型为 `[时间]`）两个变量，错误码 `isv.TEMPLATE_PARAMS_ILLEGAL`。
- **影响**：所有紧急短信通知到联系人的发送均失败（E1 已修 try-catch 不影响业务流程返回 200，但联系人实际收不到短信）。
- **方案**：采用方案 A——阿里云控制台把 `location`/`time` 模板变量类型改为"自定义"（不限制格式），无需改代码。
- **验证方式**：用户在阿里云控制台手动修改模板配置确认已生效；代码侧（`EmergencyService.formatLocation`）未改动。
- **涉及文件**：阿里云 SMS 模板配置（EMERGENCY_ALERT，仅控制台配置变更，无代码 diff）。

---

### [P1] PR1 · 账号注销流程加固：活跃订单校验补全 + PII 级联清理 — `2026-07-18` 【已确证】

- **问题**：`UserService.deleteAccount()`（账号注销功能本身从项目初始 commit 起就存在，此前 ISSUES 中"账号注销功能整体缺失/无处挂载清理步骤"的表述系误判，现予更正）存在三处缺口：
  1. 活跃订单校验只检查盲人侧，且状态列表遗漏 `DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`——盲人可在志愿者已出发/已到达时注销账号。
  2. 志愿者侧完全没有活跃订单校验——志愿者可在被派单/陪跑中随时注销，订单和盲人被晾在半空。
  3. 注销只混淆 `User.phone`，`BlindProfile`/`VolunteerProfile`（含身份证号、人脸核验 ID）、`EmergencyContact`、`RunOrderTrackPoint`（行踪轨迹，PIPL 敏感个人信息）等关联 PII 全部原样保留，OSS 证件照片也不会被清理。
- **方案**：
  - `UserService` 新增 `BLIND_ACTIVE_ORDER_STATUSES`（补齐 `DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`）与 `VOLUNTEER_ACTIVE_ORDER_STATUSES`（`PENDING_ACCEPT`/`IN_PROGRESS`/`DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`，不含 `PENDING_MATCH`/`REMATCHING`——这两个状态下 `order.volunteer` 已被 `OrderLifecycleService` 置空），复用已有 `ErrorCode.ACTIVE_ORDER_ACCOUNT_DELETION_BLOCKED`（409）。`RunOrderRepository` 新增 `existsByVolunteerIdAndStatusIn`。
  - `deleteAccount()` 通过校验后新增 `cascadeDeletePii()`：清空 `User.name`；盲人侧删 `BlindProfile`+`EmergencyContact`；志愿者侧删 `VolunteerProfile`+`VolunteerAvailableTime`，并通过新增的 `FileStorageService.delete(key)` 清理 OSS/本地证件照片（非阻断式，失败仅记日志）；两种角色统一清理 `RunOrderTrackPoint`（新增 `deleteByUserId`，覆盖用户作为盲人/志愿者两种角色产生的轨迹点）。
  - **两方记录保留策略**：`RunOrder`/`OrderReview`/`EmergencyEvent`/`OrderStatusLog` 等表不直接存储姓名/手机号（仅存 `userId`），本次不改动，仅需保证 `User` 本身正确匿名化即可满足最小必要原则，同时保留纠纷复核/审计轨迹（参考网约车行业通行做法）。
- **涉及文件**：`UserService`、`RunOrderRepository`、`BlindProfileRepository`、`VolunteerProfileRepository`、`RunOrderTrackPointRepository`、`FileStorageService`+`LocalFileStorageService`+`OssFileStorageService`；新增 `UserServiceTest`（此前零覆盖）。详见 `docs/轨迹数据留存策略.md` 第 4 节。
- **验证**：`gradlew test` 全量通过（含新增 `UserServiceTest` 覆盖活跃订单校验两侧、PII 级联删除、OSS 文件清理条件分支、越权注销拒绝）。

---

## ✅ 已解决 — 前端联调反馈（FE 系列，2026-06-22）

> 来源：前端联调阶段反馈的 6 项后端问题。详见 [CHANGELOG 1.5.0](./CHANGELOG.md#150---2026-06-22)。

### [P1] FE-1 · 志愿者可服务状态（isAvailable）查询未生效 — `2026-06-22` 【已确证】

- **问题**：前端反馈开启可服务状态后再次查询未生效。根因：后端无 `isAvailable` 字段，真正的接单开关 `wantsDispatch` 只存在 Redis（TTL 30s），不落库、`GET /profile` 不返回；Redis key 过期时 `updateDispatchStatus` 静默 return 丢失开关。
- **方案**：`wantsDispatch` 落库到 `volunteer_profile` 表（默认 true）；`GET/PUT /profile` 读写；派单候选筛选 + 接单校验以 DB 为准；`updateDispatchStatus` 即使 Redis key 不存在也落库。
- **涉及**：`VolunteerProfile`, `VolunteerProfileResponse`, `VolunteerProfileUpdateRequest`, `VolunteerService`, `VolunteerLocationService`, `DispatchService`
- **⚠️ 迁移**：生产需 `ALTER TABLE volunteer_profile ADD COLUMN wants_dispatch BOOLEAN NOT NULL DEFAULT TRUE;` → **2026-06-23 已执行**（详见 [S10](#p0-s10--生产部署遗漏-fe-1-迁移导致-crash-loop--全站-502--2026-06-23-已确证)；此前部署遗漏导致 crash-loop + 502）

### [P1] FE-2 · 接单失败缺统一错误码 — `2026-06-22` 【已确证】

- **问题**：前端反馈接单失败返回 500（实为 403/409/400，无 500），但 `OrderStatusException` 无 `errorCode`，前端只能靠 message 文本区分"已被他人接单"。
- **方案**：新建 `ErrorCode` 枚举集中管理码；`OrderStatusException` 加 `errorCode`；接单各分支返回 `ORDER_ALREADY_ACCEPTED`/`ORDER_DISPATCH_MISMATCH`/`ORDER_CONCURRENT_CONFLICT`；接单新增 `VOLUNTEER_NOT_AVAILABLE` 校验（关闭可服务状态时拒接）。
- **涉及**：`ErrorCode`, `OrderStatusException`, `GlobalExceptionHandler`, `DispatchService`

### [P1] FE-3 · 不足 30 分钟预约仍创建成功 — `2026-06-22` 【已确证】

- **问题**：`OrderCreationService` 只校验 `plannedStartTime > now()`，差 1 秒也能下单。
- **方案**：新建 `OrderTooSoonException` → HTTP 422 + `APPOINTMENT_TOO_SOON`；阈值 `app.order.min-lead-time-minutes=30`（可配）。
- **涉及**：`OrderTooSoonException`, `OrderCreationService`, `GlobalExceptionHandler`, `application.properties`

### [P1] FE-4 · 验证码错误返回 `{"error":...}` — `2026-06-22` 【已确证】

- **问题**：`handleAuthException` 返回 `Map.of("error", msg)`，与统一结构不一致。
- **方案**：`AuthException` 加 `errorCode`；统一返回 `{success,code,errorCode,message}`；新增 `INVALID_VERIFICATION_CODE`/`PHONE_FORMAT_INVALID`/`USER_NOT_FOUND`。
- **涉及**：`AuthException`, `AuthService`, `GlobalExceptionHandler`

### [P2] FE-5 · 本人读紧急联系人电话被掩码 — `2026-06-22` 【已确证】

- **问题**：`toResponse` 对所有响应无差别掩码；且"未传 phone 不更新"语义不成立（`@NotBlank` 400 + 无 null 守卫）。
- **方案**：本人读取返回明文（接口已限定仅 BLIND 本人可访问）；`updateContact` 改 PATCH 语义；放宽 `@NotBlank` 由 service 手动校验新增必填。
- **涉及**：`EmergencyContactService`, `EmergencyContactRequest`

### [P1] FE-6 · `DRIVER_ARRIVED → IN_PROGRESS` 无触发方 — `2026-06-22` 【已确证】

- **问题**：状态机无此边、无 endpoint/scheduler 触发，是设计缺口；附带 bug：卡在 `DRIVER_ARRIVED` 的超时订单永不自动完成。
- **方案**：明确"到达即开始"——`DRIVER_ARRIVED` 视为服务进行中可直接 `/finish`；修复 `findTimedOutOrders` 让 `IN_PROGRESS`/`DRIVER_EN_ROUTE`/`DRIVER_ARRIVED` 超时订单都能自动完成。
- **涉及**：`OrderStatus`（注释）, `RunOrderRepository`, `OrderTimeoutScheduler`

---


## 🟡 待处理 — P2（增强/优化）

### [P2] S18 · 走散检测阈值/连续确认次数缺乏产品书面依据 — `2026-07-20` 【⚠️ 待核实】

- **问题**：`EscortSafetyService` 的走散判定距离阈值（`app.escort.max-distance-meters`，默认 100 米）和连续确认次数（`app.escort.consecutive-breaches-required`，默认 2 次）都是工程经验值，未经产品/业务方书面确认是否符合实际陪跑场景（不同配速、城市峡谷 GPS 漂移幅度等）。
- **方案**：已把两个值从硬编码改为 `@Value` 可配置项，产品确认正式依据后运维改配置即可生效，无需重新发版。`checkDistance()` 触发前的 `log.warn` 已打印本次确认所用的 `breachCount`/`maxDistanceMeters`，方便日后从日志复核阈值是否偏保守/偏宽松。
- **涉及**：`EscortSafetyService.java`, `application.properties`
- **待办**：产品/业务方给出正式阈值依据后，更新此条目状态为已解决。

### [P2] T2 · API 文档缺口：`PacePreference` 枚举值未列举 — `2026-06-20` 【已确证】

- **问题**：`PUT /api/blind/profile` 的 `defaultPace` 字段接受 `PacePreference` 枚举，但 API 文档未列出合法值。生产冒烟测试发送 `"SLOW"` 导致 400（`SLOW` 不是合法值）。
- **合法值**：`WALK_RUN` / `EASY` / `MODERATE` / `FAST` / `NO_PREFERENCE`
- **需更新**：`docs/api/user.md`（blindProfile 请求体说明）、`docs/frontend-guide.md`

### [P2] T3 · API 文档缺口：更新通知模板字段名为 `templateText` — `2026-06-20` 【已确证】

- **问题**：`PUT /api/admin/notification-templates/{id}` 的请求体字段名为 `templateText`（对应 DB 列 `template_text`），但字段名不直观（容易误写成 `body`、`content`、`title`），生产测试曾因此返回 400。
- **需更新**：`docs/api/admin.md`（明确标注请求体示例包含 `templateText`）、`docs/frontend-guide.md`

---

## 🟢 评审确认的良好实践（保持，勿破坏）

> 评审中发现以下已做对的部分，未来维护时注意保留：

- 分布式调度锁 Watchdog（防多实例重复派单）
- Redis GEO 派单 + 乐观锁重试
- Lua 原子限流（Redis SessionCallback）
- 手机号脱敏全覆盖（`PhoneMaskUtils.mask()`）
- JWT 弱密钥黑名单（`JwtUtil.WEAK_DEFAULTS`）
- 构造器注入（无字段注入）
- CS 登录失败锁定（15min / 5 次）
- **SecurityConfig 用 `requestMatchers(HttpMethod, pattern)` 重载**（勿用 `"POST /path"` 字符串——Spring Security 6 的 MvcRequestMatcher 不解析字符串内方法前缀，S3 教训）
- **Redis Pub/Sub 跨实例 WebSocket 转发**（`UnifiedSessionRegistry` + `WebSocketMessageBroker`，多实例扩容前已就绪）

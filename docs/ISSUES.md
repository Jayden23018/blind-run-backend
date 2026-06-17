# 助盲跑后端 — 问题追踪 (Issues Tracker)

> **用途**：专门记录代码评审发现的问题/缺陷及其修复状态（已解决 / 待处理）。
> **与 `docs/TODO.md` 的区别**：`TODO.md` 是**功能开发路线图 + 技术债务**（要做什么新功能）；本文件专记**缺陷修复进度**（已有功能的问题）。
> **维护规则**：
> - 修复并验证后，将条目从「待处理」移至「已解决」，附日期 + 涉及文件。
> - 新评审发现的问题，按优先级（P0 > P1 > P2）追加到「待处理」对应区块。
> - 每条标注信息可信度：**【已确证】**（读代码/文档确认）或 **【⚠️ 待核实】**（概括，需进一步核实）。

**最近更新**：2026-06-17（P1 全部清零）

---

## 状态总览

| 优先级 | 已解决 | 待处理 |
|--------|--------|--------|
| P0（影响核心功能/安全） | ✅ 4 / 4 | 0 |
| P1（重要，应修） | ✅ 5 / 5 | 0 |
| P2（增强/优化） | 0 | 5 |

**评审来源**：2026-06-17 首次全面代码评审。

### 编号约定
- `S` = Security 安全　`A` = Availability / Accessibility 可用性·可达性　`B` = Business 业务逻辑　`V` = Volunteer 志愿者　`P` = Platform 平台·部署

---

## ✅ 已解决

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

### [P1] P1 · 多实例 WebSocket 通知（更正：已解决） — `2026-06-17` 【已确证】

- **原描述（不准确）**：`UnifiedSessionRegistry` 是本地内存，多实例通知丢失。
- **核实结论**：代码库**已实现完整的 Redis Pub/Sub 跨实例转发**——`UnifiedSessionRegistry.sendToUser` 本机无 session 时转发 Redis、`WebSocketMessageBroker` 的 `ws:messages` 频道桥接 USER/CS_BROADCAST、`RedisWebSocketConfig` 全实例订阅。生产当前单实例未承压，但跨实例机制已预先就绪。
- **处理**：无需改动代码，仅更正背景描述并标记已解决。

---

## 🟡 待处理 — P2（增强/优化）

### S6 · Swagger 生产默认开启 【已确证】

- **问题**：`OpenApiConfig` 配置 Swagger，生产默认暴露 `/swagger-ui`、`/v3/api-docs`。
- **影响**：生产环境暴露 API 结构，便于攻击者枚举端点。
- **建议**：生产 profile（`prod`）禁用或加权限校验。

### S9 · 生产 `ddl-auto=update` 【已确证】

- **问题**：Hibernate `ddl-auto=update` 在生产使用。
- **影响**：表结构漂移、非预期 DDL、生产事故风险。
- **建议**：生产改 `validate` 或 `none`，引入 Flyway/Liquibase 管理迁移。

### A3 · TTS 文案审查 【⚠️ 待核实】

- **问题**：WebSocket 消息含 `ttsText` 字段（供语音播报），文案是否适合视障场景（紧急提示是否清晰、是否过长、措辞是否友好）尚未审查。
- **涉及**：各通知模板的 `ttsText`
- **建议**：待审查所有 `ttsText`，必要时与视障用户共创。

### V1 · 志愿者超时计入拒绝率 【⚠️ 待核实】

- **问题**：`VolunteerProfile` 有 `totalDecled` / `acceptanceRate`，`DispatchService` 在 accept/decline/timeout 时更新。疑似**超时未响应**也被计入 declined。
- **影响**：志愿者超时（非主动拒绝）拉低 `acceptanceRate`，进而影响后续派单评分（`acceptance×10`），对志愿者不公。
- **建议**：超时与主动拒绝分开统计。
- **待核实**：读 `DispatchService` 确认 timeout 路径是否真的递增 `totalDeclined`。

### A8 · 订单等待期无进度反馈 【⚠️ 待核实】

- **问题**：订单 `PENDING_MATCH` / 串行派单期间，盲人对进度无感知。
- **影响**：盲人不知道派到第几个志愿者、还要等多久，产生焦虑。
- **建议**：派单轮次/进度通过 WebSocket 反馈给盲人。

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

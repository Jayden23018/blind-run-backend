# Claude Code + Postman 协同测试工作流

> **适用项目**:Blind Run(助盲跑)后端 · Spring Boot 3.4.4 / Java 17 / 端口 8081
> **文档定位**:项目维护者本人的操作手册(非通用教程),所有命令与示例均针对本项目真实接口。
> **维护规范**:接口契约/行为变更时同步本文档;新增场景按既有章节结构追加。

---

## 目录

1. [文档目的与适用范围](#1-文档目的与适用范围)
2. [环境准备:一次配置](#2-环境准备一次配置)
3. [核心场景一:spec → collection 自动同步](#3-核心场景一spec--collection-自动同步)
4. [核心场景二:跑接口测试 + 失败归因](#4-核心场景二跑接口测试--失败归因)
5. [核心场景三:API 安全审计](#5-核心场景三api-安全审计)
6. [生产环境测试注意事项](#6-生产环境测试注意事项)
7. [协同工作流速查表](#7-协同工作流速查表)
8. [附录](#8-附录)

---

## 1. 文档目的与适用范围

本文档说明如何用 **Claude Code + Postman 官方 Plugin** 协同完成本项目的三件事:

- **接口契约同步**:改 `api_spec.yaml` 后自动同步到 Postman collection(避免手动导入)
- **接口测试 + 失败归因**:跑 collection,失败时关联代码变更给修复建议
- **API 安全审计**:针对本项目 JWT 多角色 / 限流 / 手机号脱敏 / Token 黑名单 / CS 登录锁定的专项检查

### 前置条件

| 条件 | 说明 |
|------|------|
| Claude Code ≥ v1.0.33 | Postman Plugin 的最低版本要求 |
| Postman API key | 在 [postman.co/settings/me/api-keys](https://postman.postman.co/settings/me/api-keys) 申请 |
| Postman Plugin 已安装 | 本项目已通过 `/plugin` 装好,`/postman:*` 命令可用 |
| 本地服务可起 | `JWT_SECRET` 等环境变量齐全(见 [CLAUDE.md](../CLAUDE.md)「Environment variables required」),`gradlew bootRun` 能起在 8081 |
| `docs/api_spec.yaml` 存在 | 唯一契约源(67 个 operationId,卫生良好) |

### 与其他文档的关系

| 文档 | 在本工作流中的角色 |
|------|-------------------|
| [api_spec.yaml](api_spec.yaml) | **契约源**,`/postman:sync` 的输入 |
| [test-accounts.md](test-accounts.md) | 测试账号(admin/admin123 等),`/postman:test` 前置 |
| [websocket-protocol.md](websocket-protocol.md) | WS 协议,Postman sync **不覆盖**,需手工测(见 [§3 常见坑](#常见坑)) |
| [deployment.md](deployment.md) | 生产部署,见 [§6](#6-生产环境测试注意事项) |
| [CHANGELOG.md](CHANGELOG.md) | 契约/行为变更后追加版本记录 |

---

## 2. 环境准备:一次配置

### 2.1 确认 Postman Plugin 已就绪

```
/postman:setup
```

走 OAuth 或填 Postman API key,选择目标 workspace(建议为本项目单独建一个 `Blind Run` workspace)。输出会列出当前 workspace,即配置成功。

### 2.2 配置本地 full MCP(测 localhost 8081 必需)

⚠️ **关键坑**:本项目跑在 `127.0.0.1:8081`。Postman **remote MCP**(默认 `minimal`,37 工具)**无法访问 localhost**。要测本地服务,必须用本地 `full` 模式:

```bash
claude mcp add postman --env POSTMAN_API_KEY=你的PMAK密钥 -- \
  npx @postman/postman-mcp-server@latest --full
```

`--full` 模式开放 100+ 工具,其中包含访问 localhost 的能力。验证:

```bash
claude mcp list
# 应看到 postman,且能通过它访问 127.0.0.1:8081
```

> 对比:remote minimal 适合做 collection/environment 管理(不需要打本地);**只有要跑真实本地接口测试时才需要本地 full**。两者可并存。

### 2.3 Spring Boot Skills(可选)

社区维护的 [springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) 提供 4 个针对 Spring Boot 的 skill:

```
/plugin marketplace add a-pavithraa/springboot-skills-marketplace
/plugin enable a-pavithraa/springboot-skills-marketplace
/plugin install springboot-architecture@springboot-skills-marketplace
```

| Skill | 本项目对应痛点 |
|-------|---------------|
| `spring-data-jpa` | **治 N+1**:DispatchService 5 维评分、EmergencyService 多表关联是 N+1 高发区;DTO 投影、CQRS 查询服务 |
| `code-reviewer` | PR 阶段 review:查 `PhoneMaskUtils` 是否漏调、`JwtFilter` 黑名单是否检查、虚拟线程 pinning |
| `creating-springboot-projects` | 核对脚手架约定(Gradle / Java 17 / Spring Boot 3.4.4 版本对齐) |
| `springboot-migration` | 暂留作未来升级(Boot 3.x → 更高版本)时用 |

> **本项目现状**:已安装 `everything-claude-code` 插件,其中**已包含** `java-reviewer`、`java-build-resolver`、`security-reviewer`、`code-reviewer`、`tdd-guide` 等 agent,覆盖了上表的 `code-reviewer` 需求。本插件的核心增量是 **`spring-data-jpa`**(JPA / N+1 专项)——如需要再装,否则可跳过避免插件臃肿。Skill 不用手动调用,Claude 会根据请求自动加载。

| Skill | 本项目对应痛点 |
|-------|---------------|
| `spring-data-jpa` | **治 N+1**:DispatchService 5 维评分、EmergencyService 多表关联是 N+1 高发区;DTO 投影、CQRS 查询服务 |
| `code-reviewer` | PR 阶段 review:查 `PhoneMaskUtils` 是否漏调、`JwtFilter` 黑名单是否检查、虚拟线程 pinning |
| `creating-springboot-projects` | 核对脚手架约定(Gradle / Java 17 / Spring Boot 3.4.4 版本对齐) |
| `springboot-migration` | 暂留作未来升级(Boot 3.x → 更高版本)时用 |

> Skill 不用手动调用,Claude 会根据请求自动加载(如「帮我优化这个 repository 的查询」→ 自动用 `spring-data-jpa`)。

### 2.4 本地启动校验

```bash
# 起服务(macOS 用 127.0.0.1,不要用 localhost,见 CLAUDE.md gotcha)
source start-with-correct-env.sh && JWT_SECRET="any-strong-random-key-32bytes" \
  /Users/mac/Downloads/demo/gradlew -p /Users/mac/Downloads/demo bootRun

# 另一窗口验证 api-docs 可访问
curl -s http://127.0.0.1:8081/v3/api-docs | head -c 200
```

---

## 3. 核心场景一:spec → collection 自动同步

### 触发时机

当你改了 `docs/api_spec.yaml`(新增端点 / 改 DTO / 改状态码)之后。

### 操作

```
/postman:sync
```

或自然语言:「把 docs/api_spec.yaml 的变更同步到 Postman collection」。

Plugin 会:
1. 读取本地 `docs/api_spec.yaml`
2. 对比 Postman workspace 里既有的 collection
3. **覆盖更新**(不是新建)——新增端点加入、删除端点标记、修改端点更新

### 项目示例

**场景**:新增了 `PUT /api/volunteer/dispatch-status`(志愿者开关可服务状态,FE-1 改动)。

1. 先在 `api_spec.yaml` 对应 paths 下补上该端点的 operationId + request/response schema
2. 运行 `/postman:sync`
3. Postman collection 的 `Volunteer` 文件夹下自动出现 `PUT /dispatch-status` 请求

### 预期产出

- sync 报告:本次新增 N 个、修改 M 个、删除 K 个请求
- collection 与 spec 一致,无漂移

### 常见坑

| 坑 | 说明 / 处理 |
|----|------------|
| **WebSocket 端点不被覆盖** | `/ws/blind` `/ws/volunteer` 不在 OpenAPI HTTP paths 里,sync 不会动它们。WS 测试需按 [websocket-protocol.md](websocket-protocol.md) 用 wscat 手工维护,collection 里单独放一个 `WebSocket(手工)` 文件夹说明 |
| **operationId 重复** | 本项目当前 67 个 operationId **全部唯一**(已核实),无此问题。若未来新增端点,确保 operationId 唯一,sync 才不会错乱 |
| **选错 workspace** | sync 前用 `/postman:setup` 确认指向 `Blind Run` workspace,避免同步到团队公共区 |

---

## 4. 核心场景二:跑接口测试 + 失败归因

### 4.1 测试前置准备

| 准备项 | 说明 |
|--------|------|
| **测试账号** | 见 [test-accounts.md](test-accounts.md)。CS 管理员:`admin` / `admin123`;盲人/志愿者账号需走注册流程 |
| **4 种角色 token** | 见 [附录 D](#附录-d4-种角色-token-获取模板);每个角色一个 collection 文件夹 |
| **验证码获取** | 登录走短信验证码,Redis key `sms:code:{phone}`;**本地测试看服务日志**取验证码(见 CLAUDE.md) |
| **⚠️ 限流** | 见下 |

### ⚠️ 关键坑:限流(429)

**单元测试**里 `rate-limit.enabled=false` 已关;但 Postman 打**真实 8081 服务时限流是开的**。三个 bucket:

- `auth`(10/min):`/api/auth/*`、`/api/cs/auth/*`
- `registration`(20/min):`/api/volunteer/registration/*`
- `general`(60/min):其他 `/api/*`

**高频跑 collection 会大面积 429**。两种应对:
1. **collection 间加延迟**:在 Postman collection 级设 `Delay between requests`(如 1500ms)
2. **临时调 bucket**:本地调试时改 `application.properties` 的限流阈值,跑完改回

### 4.2 订单状态机串联测试(最有项目特色的用例)

本项目的核心流程是订单状态机。一个完整的串联 collection 应覆盖:

```
BLIND 创建订单
  POST /api/orders                    (BLIND token)  → 期望 200, status=PENDING_MATCH
       ↓
派单服务触发(自动,见 DispatchService)
       ↓
VOLUNTEER 响应派单
  POST /api/orders/{id}/respond       (VOLUNTEER token, body: {action:ACCEPT})
                                                     → 期望 200, status=PENDING_ACCEPT → 异步 IN_PROGRESS
       ↓
  POST /api/orders/{id}/en-route      (VOLUNTEER token) → status=DRIVER_EN_ROUTE
  POST /api/orders/{id}/arrived       (VOLUNTEER token) → status=DRIVER_ARRIVED
  POST /api/orders/{id}/finish        (VOLUNTEER token) → status=COMPLETED
```

> 注意:本项目用**串行派单**(`/respond`,不是旧的 `/accept` `/reject`)。`/accept` `/reject` 已 `@Deprecated`,内部委托给 `dispatchService.handleVolunteerResponse`,行为一致。前端统一用 `/respond`。

### 4.3 操作

```
/postman:test
```

或自然语言:「跑一下订单接口的 collection」。

Plugin 会:
1. 执行 collection 里的所有请求(带上 collection/environment 配的 token、变量)
2. 解析每个请求的 test script 断言
3. **失败归因**:把 4xx/5xx 失败请求,关联到最近 `git diff` 的代码文件,给出可能的修复建议

### 4.4 失败归因示例

假设 `/api/orders/{id}/respond` 返回 `403 errorCode=VOLUNTEER_NOT_AVAILABLE`:

- `/postman:test` 输出会指向:`DispatchService.handleVolunteerResponse` / `VolunteerProfile.wants_dispatch`(对应 FE-1:志愿者关闭可服务状态时接单被拒)
- 建议检查:测试用的志愿者账号 `wants_dispatch` 是否为 true(`GET /api/volunteer/profile`)

### 常见坑

| 坑 | 处理 |
|----|------|
| **token 过期** | JWT 有 TTL,跑长 collection 时 token 失效 → 401。建议 collection 级 pre-request script 检测 401 自动重登 |
| **Redis 残留状态** | `proximity:notified:{orderId}`、`jwt:blacklist:{userId}` 没清理会跨用例污染。跑完一批用 `redis-cli FLUSHDB`(本地)或针对性 DEL |
| **TokenBlacklist 拉黑** | 登出测试后 token 进黑名单,后续复用同一 token 会 401。登出用例放 collection 最后,或每用例独立取 token |
| **CS 登录锁定** | 对 `/api/cs/auth/login` 故意错密码 5 次会锁 15 分钟(Redis `cs:login:lock:{username}`)。锁定测试隔离跑 |

---

## 5. 核心场景三:API 安全审计

### 操作

```
/postman:security
```

Plugin 跑 OWASP API Top 10 的 20+ 项检查,每项给严重度评分 + 修复建议。

### ⚠️ 强烈建议:安全审计单独跑

安全审计会高频打接口,**极易触发限流和 CS 锁定**,污染后续正常测试。建议:
- 单独开一个 Claude Code session 跑安全审计
- 跑完清 Redis(本地:`redis-cli FLUSHDB`)
- 不要和安全测试之外的用例混跑

### 针对本项目 5 个安全特性的专项检查清单

| # | 安全特性 | 检查方法 | 期望结果 | OWASP 映射 |
|---|---------|---------|---------|-----------|
| 1 | **JWT 多角色越权** | 用 BLIND token 访问 `GET /api/admin/*`、`GET /api/cs/*`;用 VOLUNTEER token 访问 `GET /api/blind/profile` | 应返回 **403**(JSON `{success:false,code:403,...}`) | API1 BOLA / API2 Broken Auth |
| 2 | **RoleController 换 token** | 调 `POST /api/auth/role` 设置角色后,**用旧 token** 访问受保护端点 | 旧 token 应失效(角色已变),需用返回的新 token | API2 Broken Auth |
| 3 | **限流 429 结构** | 对 `POST /api/auth/send-code` 连打 11 次(超 auth bucket 10/min) | 第 11 次返回 `{error,message,retryAfterSeconds}` + `Retry-After` 响应头 | API4 Unlimited Consumption |
| 4 | **手机号脱敏** | `GET` 任何返回 phone 的端点(admin 查志愿者列表等) | 响应是 `138****1234` 格式,**非明文** | API3 Excessive Data Exposure |
| 4b | **脱敏例外(合法)** | 盲人查 `GET /api/users/{userId}/emergency-contacts`(本人) | 返回**明文**电话 —— 这是 FE-5 的合法设计(仅本人可访问,接口已限定 BLIND 角色 + JWT=路径 userId)。**审计时不要误报** | — |
| 5 | **TokenBlacklist** | 登出 `POST /api/auth/logout` 后,用旧 token 访问 `GET /api/orders/mine` | 应 **401**(token 已进 `jwt:blacklist:{userId}`) | API2 Broken Auth |
| 6 | **CS 登录锁定** | 对 `POST /api/cs/auth/login` 连续错密码 5 次 | 第 6 次返回锁定提示,Redis `cs:login:lock:{username}` 存在 15 分钟 | API2 Broken Auth |

### 安全审计示例端点(已确认存在于 api_spec.yaml)

- `/api/auth/send-code` `/api/auth/verify-code`(限流 + 验证码)
- `/api/cs/auth/login`(CS 锁定)
- `/api/admin/volunteers/review/{id}`(越权检查)
- `/api/users/{userId}/emergency-contacts`(脱敏例外)
- `/api/orders/{id}/respond`(状态机 + 角色)

### 预期产出

- OWASP 报告:每项 pass / fail + 严重度
- 修复建议指向具体的 service / config 类(如 `RateLimitInterceptor`、`JwtFilter`、`PhoneMaskUtils`)

---

## 6. 生产环境测试注意事项

生产地址:阿里云 `47.114.113.171`(systemd service `blindrun`,Nginx 反代 80→8081)。

### 🔴 红线:生产绝不跑破坏性测试

以下操作**会污染生产 Redis / 影响真实用户**,生产环境禁止:

| 禁止操作 | 后果 |
|---------|------|
| `POST /api/orders`(创建订单) | 产生垃圾订单 |
| `POST /api/orders/{id}/cancel` | 干扰真实派单 |
| `POST /api/emergency`(触发紧急) | 触发真实短信告警 + 推客服 |
| 对 `/api/cs/auth/login` 错密码 5 次 | **生产 Redis 锁定 admin 15 分钟**,真实管理员登不上 |
| 高频打 `/api/auth/send-code` | 消耗阿里云短信额度 + 触发限流 |

### 生产只做:只读 GET + 被动安全检查

- **只读 smoke collection**:用专用只读测试账号,只覆盖:
  - `GET /api/auth/me`(确认 token 有效)
  - `GET /api/blind/profile`(确认服务正常)
  - (本项目未引入 actuator,无 `/actuator/health`;以业务只读接口代替)
- **被动安全审计**:只看**响应头 / 脱敏格式**,**不做越权打点**(越权打点会触发真实告警 / 锁定 / 限流)。

### token 与密钥安全

- 生产 `JWT_SECRET` **绝不**进 Postman collection 变量或 environment 明文 —— 用 Postman 的 **secret 类型变量**
- 测试 token 走 secret 变量;`baseUrl` 走普通变量,分 local / staging / prod 三套 environment,避免误打生产

---

## 7. 协同工作流速查表

| 时机 | 命令 | 产出 |
|------|------|------|
| 改了 `api_spec.yaml` | `/postman:sync` | collection 自动更新 |
| 提交前自测 | `/postman:test` | 通过率 + 失败归因 |
| 改了 JWT / 脱敏 / 权限 | `/postman:security` | OWASP 报告 |
| 前端联调要 mock | `/postman:mock` | mock server URL |
| 生成客户端代码(TS/Java) | `/postman:codegen` | 强类型客户端 |
| 查接口在 collection 哪 | `/postman:search` 或自然语言 | 定位 |
| 单个请求快测 | `/postman:send-request` | 响应 |
| spec 质量评估 | `/postman:agent-ready-apis` | API Readiness 8 支柱评分 |
| WS 调试 | **手工**(wscat) | 见 [websocket-protocol.md](websocket-protocol.md) |
| 写新 JPA 查询 / repository | agent:`java-reviewer`(已装)或 skill:`spring-data-jpa`(需另装) | 防 N+1 |
| PR review | agent:`code-reviewer` / `security-reviewer`(均已装) | 架构 + 性能 + 安全 review |

### 自然语言触发(不用记命令)

Plugin 后台的 Routing skill 会从自然语言识别意图,自动路由到对应命令。直接说:

- 「把 api_spec.yaml 同步到 Postman」→ `/postman:sync`
- 「跑一下订单接口测试」→ `/postman:test`
- 「检查一下接口有没有安全漏洞」→ `/postman:security`
- 「给前端生成一个 TypeScript 客户端」→ `/postman:codegen`

---

## 8. 附录

### 附录 A:环境变量速查

| 变量 | 用途 | 来源 |
|------|------|------|
| `JWT_SECRET` | JWT 签名(≥32 字节,dev 默认值已被黑名单) | 必须设置,否则登录失败 |
| `DB_PASSWORD` | MySQL 密码 | CLAUDE.md |
| `ALIYUN_ACCESS_KEY_ID` / `SECRET` | 阿里云 OSS / SMS / CloudAuth | `start-with-correct-env.sh` |
| `ALIYUN_SMS_SIGN_NAME` | 短信签名(深圳醋溜科技) | env |
| `POSTMAN_API_KEY` | Postman MCP 认证(PMAK 开头) | postman.co 申请 |

### 附录 B:Redis key 速查(测试清缓存用)

| Key 模式 | 含义 | 清理场景 |
|---------|------|---------|
| `sms:code:{phone}` | 短信验证码(TTL 5min) | 重测登录 |
| `jwt:blacklist:{userId}` | 登出 token 黑名单 | 登出测试后 |
| `cs:login:lock:{username}` | CS 登录锁定(TTL 15min) | 锁定测试后 |
| `cs:login:attempts:{username}` | CS 登录失败计数 | 同上 |
| `proximity:notified:{orderId}` | 接近通知去重 | 订单测试跨用例 |
| `blind:loc:{userId}` / `vol:loc:{userId}` | 实时位置(TTL 30s) | 位置测试 |
| `rate_limit:*` | 限流计数 | 限流测试后 |

本地清理:`redis-cli FLUSHDB`(清当前库)或针对性 `redis-cli DEL <key>`。

### 附录 C:订单状态机流转图

```
PENDING_MATCH ──派单──► PENDING_ACCEPT ──accept──► IN_PROGRESS
      │                       │                        │
      │                       │                 en-route│
      │                  volunteer cancel             ▼
      │                       │               DRIVER_EN_ROUTE
      ▼                       │                        │
  NO_VOLUNTEER                │                  arrived│
                              ▼                        ▼
                         REMATCHING              DRIVER_ARRIVED
                              │                        │
                       (重新派单)                  finish│
                                                       ▼
                          CANCELLED ◄──blind cancel─ COMPLETED
```

> 三个「进行中」状态(`IN_PROGRESS` / `DRIVER_EN_ROUTE` / `DRIVER_ARRIVED`)均可直接 `/finish`。

### 附录 D:4 种角色 token 获取模板

**CS 管理员(账号密码登录,无需验证码):**
```bash
curl -X POST http://127.0.0.1:8081/api/cs/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 响应含 token,JWT 里带 csRole=CS_ADMIN
```

**盲人 / 志愿者(验证码登录,验证码看服务日志):**
```bash
# 1. 发验证码(注意限流 auth 10/min)
curl -X POST http://127.0.0.1:8081/api/auth/send-code \
  -H "Content-Type: application/json" \
  -d '{"phone":"13xxxxxxxxx"}'
# → 服务日志里看 sms:code:{phone} 的验证码

# 2. 验证码登录
curl -X POST http://127.0.0.1:8081/api/auth/verify-code \
  -H "Content-Type: application/json" \
  -d '{"phone":"13xxxxxxxxx","code":"日志里的验证码"}'
# → 返回 token(userId 作 subject)

# 3. 设置角色(返回新 token,客户端必须替换)
curl -X POST http://127.0.0.1:8081/api/auth/role \
  -H "Authorization: Bearer <上一步token>" \
  -H "Content-Type: application/json" \
  -d '{"role":"BLIND"}'   # 或 VOLUNTEER
# → 响应含新 token(带 role claim)
```

> 志愿者还需完成 4 步注册(BASIC_INFO→ID_UPLOAD→FACE_VERIFY→TRAINING→COMPLETED)才能接单,详见 [test-accounts.md](test-accounts.md)。

### 附录 E:常见错误码映射

| HTTP | errorCode | 含义 | 触发场景 |
|------|-----------|------|---------|
| 401 | — | 未认证 / token 失效 / token 黑名单 | 未带 token、token 过期、登出后旧 token |
| 403 | — | 角色越权 | BLIND token 打 `/api/admin/*` |
| 403 | `VOLUNTEER_NOT_AVAILABLE` | 志愿者可服务状态关闭 | `wants_dispatch=false` 时 `/respond` |
| 403 | `ORDER_DISPATCH_MISMATCH` | 订单未派送给您 | 非当前派单志愿者 `/respond` |
| 404 | — | 订单/资源不存在 | 接单时订单不存在(B3 修复:返回 404 非 500) |
| 409 | `ORDER_ALREADY_ACCEPTED` | 订单已被他人接单 | 并发接单 |
| 409 | `ORDER_STATUS_NOT_ALLOWED` | 状态机不允许该操作 | 如对 COMPLETED 订单 `/finish` |
| 422 | `APPOINTMENT_TOO_SOON` | 预约时间不足提前量 | plannedStartTime < now + 30min |
| 429 | — | 限流 | 超出 bucket(`{error,message,retryAfterSeconds}` + `Retry-After` 头) |

### 附录 F:参考链接

- [Announcing the Postman Plugin for Claude Code(官方博客,2026.3)](https://blog.postman.com/announcing-the-postman-plugin-for-claude-code/)
- [postman-claude-code-plugin(GitHub)](https://github.com/Postman-Devrel/postman-claude-code-plugin)
- [Automating the API Lifecycle with the MCP Server(Headless 工作流)](https://blog.postman.com/headless-postman-automating-the-api-lifecycle-with-the-mcp-server/)
- [Postman MCP Server 官方文档](https://learning.postman.com/docs/reference/postman-api/postman-mcp-server/overview)
- [springboot-skills-marketplace(GitHub)](https://github.com/a-pavithraa/springboot-skills-marketplace)
- [Claude Code Template for Spring Boot(Piotr Minkowski)](https://piotrminkowski.com/2026/03/24/claude-code-template-for-spring-boot/)

---

## 变更后必做清单(对接项目文档维护规范)

每次用本工作流改动接口契约 / 行为后,按 [CLAUDE.md](../CLAUDE.md)「文档维护工作流」同步:

- [ ] 改了 `api_spec.yaml` → 运行 `/postman:sync`,并在 [frontend-guide.md](frontend-guide.md) 同步契约
- [ ] 接口行为变化(状态机 / 错误码)→ 更新 [CHANGELOG.md](CHANGELOG.md)(用户/前端视角)
- [ ] 缺陷修复涉及接口 → 更新 [ISSUES.md](ISSUES.md)(开发者视角)
- [ ] WS 消息变化 → 更新 [websocket-protocol.md](websocket-protocol.md)
- [ ] 测试账号变化 → 更新 [test-accounts.md](test-accounts.md)

> ⚠️ **CLAUDE.md 漂移提示**:CLAUDE.md 引用了 `docs/api/`(7 份 feature 文档)和 `POSTMAN_TEST_GUIDE.md`,但**实际都不存在**——接口契约集中在 `api_spec.yaml` + `frontend-guide.md`。这是 CLAUDE.md 待修项,不在本文档范围。

---

**参考来源**:
- [Postman Plugin 官方公告](https://blog.postman.com/announcing-the-postman-plugin-for-claude-code/)
- [Postman MCP Headless 工作流](https://blog.postman.com/headless-postman-automating-the-api-lifecycle-with-the-mcp-server/)
- [Spring Boot Skills Marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace)
- [Claude Code Spring Boot 模板(Piotr)](https://piotrminkowski.com/2026/03/24/claude-code-template-for-spring-boot/)

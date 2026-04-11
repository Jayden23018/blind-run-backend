# 助盲跑 (Blind Run) 后端系统

连接盲人跑者与志愿者的陪跑预约平台后端。盲人用户可下单预约陪跑服务，系统基于地理位置自动匹配附近的认证志愿者，通过 WebSocket 实时推送订单通知。

## 技术栈

- **Java 17** + **Spring Boot 3.4.4**
- **MySQL** (数据存储) + **Redis** (缓存/位置/验证码)
- **JWT** 无密码认证（手机号 + 短信验证码）
- **WebSocket** 实时推送
- **Gradle** 构建

## 快速启动

### 环境要求

- JDK 17+
- MySQL 8.0+（创建 `spring_demo` 数据库）
- Redis

### 配置

修改 `src/main/resources/application.properties` 中的数据库和 Redis 连接信息。

### 运行

```bash
./gradlew bootRun
```

服务启动在 http://localhost:8081

### 测试

```bash
./gradlew test          # 107 项常规测试
./gradlew slowTest      # 3 项慢速测试（超时、并发）
# 共 110 项测试
```

## 项目结构

```
src/main/java/com/example/demo/
├── config/          # 安全配置（SecurityConfig）
├── controller/      # 14 个 REST 控制器
├── service/         # 20+ 个业务服务（+ impl/ 子包）
├── repository/      # 15 个 JPA Repository
├── entity/          # 数据实体 + 枚举
├── dto/             # 请求/响应 DTO
├── event/           # 异步事件（订单创建触发匹配）
├── exception/       # 自定义异常 + 全局异常处理
├── filter/          # JWT 过滤器、WebSocket 握手拦截器
├── scheduler/       # 定时任务（TimeoutScheduler、OrderTimeoutScheduler）
├── websocket/       # WebSocket 处理器、UnifiedSessionRegistry
└── util/            # 工具类（地理距离计算、JWT、手机号脱敏）
```

## 核心流程

```
盲人下单 → 异步匹配附近志愿者 → WebSocket 推送通知 → 志愿者接单 → 出发 → 到达 → 完成服务 → 评价
```

### 订单状态机

`PENDING_MATCH` → `PENDING_ACCEPT` → `IN_PROGRESS` → `DRIVER_EN_ROUTE` → `DRIVER_ARRIVED` → `COMPLETED`

- 各阶段可取消（`CANCELLED`），志愿者取消后进入重新匹配（`REMATCHING`）
- 匹配超时、重新匹配超时、超时挂起订单均通过 DB 轮询检测（`TimeoutScheduler`）
- 紧急事件可在服务中触发，30s 内志愿者未响应则自动升级通知紧急联系人

### 定时轮询（TimeoutScheduler）

| 任务 | 频率 | 说明 |
|------|------|------|
| 紧急事件志愿者超时 | 10s | 30s 未响应 → 升级通知紧急联系人 |
| 重新匹配超时提醒 | 10s | 循环提醒盲人用户 |
| 匹配超时提醒 | 10s | 循环提醒盲人"暂无志愿者" |
| 超时挂起订单 | 60s | 超过结束时间 1 小时的进行中订单 |

## 接口测试

- **Swagger UI**: http://localhost:8081/swagger-ui/index.html
- **API 文档 JSON**: http://localhost:8081/v3/api-docs （可导入 Postman）
- **Postman 测试指南**: 参见 [POSTMAN_TEST_GUIDE.md](POSTMAN_TEST_GUIDE.md)

## 详细文档

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统架构指南 |
| [API_REFERENCE.md](docs/API_REFERENCE.md) | 完整 API 参考 |
| [DIAGRAMS.md](docs/DIAGRAMS.md) | 架构图、流程图、ERD |

## API 概览

| 模块 | 接口 |
|------|------|
| 用户认证 | `POST /api/auth/send-code`、`POST /api/auth/verify-code`、`GET /api/auth/me` |
| 客服认证 | `POST /api/cs/auth/login` |
| 角色 | `POST /api/user/role` |
| 盲人 | `GET/PUT /api/blind/profile`、`POST /api/blind/location` |
| 紧急联系人 | `GET/POST/PUT/DELETE /api/users/{userId}/emergency-contacts`、`PUT .../set-primary` |
| 志愿者 | `GET/PUT /api/volunteer/profile`、`POST .../verification`、`GET .../verification/status`、`POST .../location` |
| 订单 | `POST /api/orders`、`POST .../accept\|reject\|finish\|cancel\|en-route\|arrived`、`GET .../available\|mine\|{id}\|status-logs`、`PUT .../{id}/keep-waiting` |
| 紧急事件 | `POST /api/emergency/trigger`、`PUT /api/emergency/{id}/volunteer-response` |
| 客服端 | `POST /api/cs/auth/login`、`GET /api/cs/emergency-events`、`PUT .../{id}/accept\|notify-contact\|resolve\|false-alarm` |
| 通话 | `POST /api/orders/{id}/call/initiate`、`GET .../call/records` |
| 评价 | `POST /api/orders/{id}/review`、`GET .../reviews` |
| 用户 | `GET /api/users/{id}`、`DELETE /api/users/{id}` |

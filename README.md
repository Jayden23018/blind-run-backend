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
./gradlew test          # 106 项常规测试
./gradlew slowTest      # 3 项慢速测试（超时、并发）
```

## 项目结构

```
src/main/java/com/example/demo/
├── config/          # 安全配置、WebSocket 配置
├── controller/      # 7 个 REST 控制器
├── service/         # 12 个业务服务
├── repository/      # 7 个 JPA Repository
├── entity/          # 11 个数据实体
├── dto/             # 16 个请求/响应 DTO
├── event/           # 异步事件（订单创建触发匹配）
├── exception/       # 自定义异常 + 全局异常处理
├── filter/          # JWT 过滤器、WebSocket 握手拦截器
├── scheduler/       # 定时任务（超时自动完成）
├── websocket/       # WebSocket 处理器、会话注册表
└── util/            # 工具类（地理距离计算、手机号脱敏）
```

## 核心流程

```
盲人下单 → 异步匹配附近志愿者 → WebSocket 推送通知 → 志愿者接单 → 完成服务 → 评价
```

### 订单状态

`PENDING_MATCH` → `PENDING_ACCEPT` → `IN_PROGRESS` → `COMPLETED`

（各阶段均可取消，超时自动完成）

## 接口测试

- **Swagger UI**: http://localhost:8081/swagger-ui/index.html
- **API 文档 JSON**: http://localhost:8081/v3/api-docs （可导入 Postman）

## 详细文档

| 文档 | 说明 |
|------|------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统架构指南 |
| [API_REFERENCE.md](docs/API_REFERENCE.md) | 完整 API 参考 |
| [DIAGRAMS.md](docs/DIAGRAMS.md) | 架构图、流程图、ERD |

## API 概览

| 模块 | 接口 |
|------|------|
| 认证 | `POST /api/auth/send-code`、`POST /api/auth/verify-code`、`GET /api/auth/me` |
| 角色 | `POST /api/user/role` |
| 盲人 | `GET/PUT /api/blind/profile` |
| 志愿者 | `GET/PUT /api/volunteer/profile`、`POST .../verification`、`POST .../location` |
| 订单 | `POST /api/orders`、`POST .../accept`、`POST .../finish`、`POST .../cancel` |
| 评价 | `POST /api/orders/{id}/review` |
| 用户 | `GET /api/users/{id}`、`DELETE /api/users/{id}` |

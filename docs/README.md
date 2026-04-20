# 助盲跑 Backend

盲人陪跑志愿服务平台后端服务，为视障用户提供志愿者陪跑匹配、实时通信、紧急求助等功能。

## 项目简介

助盲跑App是一个连接视障用户和志愿者的陪跑服务平台。盲人用户可以通过App发起陪跑请求，系统自动匹配附近的志愿者，志愿者接单后提供陪跑服务。系统支持实时位置追踪、紧急求助、隐私号通话等功能，保障服务过程的安全和便捷。

## 技术栈

- **框架**: Spring Boot 3.4.4
- **JDK**: Java 17
- **数据库**: MySQL 8.x
- **缓存**: Redis (位置存储、限流、会话管理)
- **实时通信**: WebSocket (订单推送、通知)
- **认证**: JWT (io.jsonwebtoken:jjwt-api:0.12.5)
- **API文档**: SpringDoc OpenAPI 2.8.6
- **外部服务**:
  - 阿里云隐私号服务
  - 短信服务 (当前为模拟实现)

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.x
- Redis 6.x
- Gradle 8.x

### 数据库初始化

```sql
CREATE DATABASE spring_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 启动步骤

1. **克隆项目**
```bash
git clone https://github.com/Jayden23018/blind-run-backend.git
cd blind-run-backend
```

2. **配置环境变量**
编辑 `src/main/resources/application.properties`，修改数据库和Redis连接信息。

3. **启动服务**
```bash
./gradlew bootRun
```

服务将在 `http://localhost:8081` 启动。

4. **访问API文档**
```
http://localhost:8081/swagger-ui/index.html
```

## 环境变量配置

### 数据库配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/spring_demo` | MySQL连接URL |
| `spring.datasource.username` | `root` | 数据库用户名 |
| `spring.datasource.password` | (空) | 数据库密码 |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate自动建表策略 |
| `spring.jpa.show-sql` | `true` | 是否在控制台打印SQL |
| `spring.sql.init.mode` | `always` | 是否执行data.sql初始化数据 |

### Redis配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.data.redis.host` | `localhost` | Redis主机地址 |
| `spring.data.redis.port` | `6379` | Redis端口 |
| `spring.data.redis.timeout` | `3000ms` | 连接超时时间 |

### 匹配算法配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.matching.max-distance-km` | `10` | 最大匹配距离（公里） |
| `app.matching.max-candidates` | `3` | 最多推送志愿者数量 |

### WebSocket配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.websocket.endpoint` | `/ws/volunteer` | WebSocket端点路径 |

### 位置服务配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.volunteer.location-ttl-seconds` | `30` | 志愿者位置TTL（秒） |
| `app.proximity.threshold-meters` | `100` | 接近提醒阈值（米） |

### 紧急事件配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.emergency.cooldown-seconds` | `60` | 紧急求助冷却时间（秒） |
| `app.emergency.volunteer-timeout-seconds` | `30` | 志愿者响应超时（秒） |

### 隐私号配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `aliyun.private-number.enabled` | `false` | 是否启用阿里云隐私号 |
| `aliyun.private-number.access-key-id` | - | 阿里云AccessKey ID (生产环境必填) |
| `aliyun.private-number.access-key-secret` | - | 阿里云AccessKey Secret (生产环境必填) |
| `aliyun.private-number.pool-key` | - | 隐私号池Key (生产环境必填) |

### 文件上传配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `app.upload.dir` | `/tmp/blindrun-uploads/` | 上传文件存储目录（生产环境需使用绝对路径） |
| `spring.servlet.multipart.max-file-size` | `10MB` | 最大文件大小 |
| `spring.servlet.multipart.max-request-size` | `10MB` | 最大请求大小 |

### 速率限制配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `rate-limit.enabled` | `true` | 是否启用限流 |
| `rate-limit.auth.max-requests` | `10` | 认证接口限流（次/分钟） |
| `rate-limit.auth.window-seconds` | `60` | 认证接口限流窗口（秒） |
| `rate-limit.registration.max-requests` | `20` | 注册接口限流（次/分钟） |
| `rate-limit.registration.window-seconds` | `60` | 注册接口限流窗口（秒） |
| `rate-limit.general.max-requests` | `60` | 通用接口限流（次/分钟） |
| `rate-limit.general.window-seconds` | `60` | 通用接口限流窗口（秒） |

### JWT配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `jwt.secret` | (内置默认值) | JWT签名密钥（生产环境必须替换） |
| `jwt.expiration` | `86400000` | Token有效期（毫秒，默认24小时） |

### 短信配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `sms.code.length` | `6` | 验证码位数 |
| `sms.code.ttl-minutes` | `5` | 验证码有效期（分钟） |
| `sms.code.max-attempts` | `5` | 最大尝试次数 |

## API文档访问

### Swagger UI
```
http://localhost:8081/swagger-ui/index.html
```

### OpenAPI JSON
```
http://localhost:8081/v3/api-docs
```

## 文档导航

- [架构说明](./architecture.md) - 系统架构、核心业务流程
- [API文档](./api/) - 按功能模块分类的API接口文档
  - [认证API](./api/auth-api.md) - 用户认证、客服登录
  - [用户API](./api/user-api.md) - 用户管理、档案管理
  - [注册API](./api/registration-api.md) - 志愿者注册流程
  - [订单API](./api/order-api.md) - 订单管理、状态流转
  - [紧急求助API](./api/emergency-api.md) - 紧急求助、联系人管理
  - [管理API](./api/admin-api.md) - 管理员审核、培训管理
  - [WebSocket API](./api/websocket-api.md) - 实时通信协议
- [部署指南](./deployment.md) - 生产环境部署配置
- [变更日志](./CHANGELOG.md) - 版本更新记录

## 开发指南

### 编译项目
```bash
./gradlew compileJava
```

### 运行测试
```bash
# 运行所有测试
./gradlew test

# 运行慢速测试（包含OrderTimeoutTest和ConcurrencyTest）
./gradlew slowTest -x test
```

### 远程调试
在IDE中添加远程JVM调试配置，端口5005：
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

## 许可证

MIT License

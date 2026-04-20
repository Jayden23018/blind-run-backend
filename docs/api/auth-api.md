# 认证API文档

## 概述

认证API提供用户登录、客服登录等功能。所有认证端点均为公开端点，无需JWT token即可访问。

## 用户认证（短信验证码登录）

### 1. 发送验证码

**接口**: `POST /api/auth/send-code`

**认证**: 无需认证

**请求头**:
```
Content-Type: application/json
```

**请求体**:
```json
{
  "phone": "13800138000"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | string | 是 | 手机号（11位） |

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**失败响应**:
- **400 Bad Request** - 参数校验失败
```json
{
  "success": false,
  "code": 400,
  "message": "手机号不能为空"
}
```

**业务逻辑**:
- 生成6位随机验证码
- 存储到Redis，TTL 5分钟
- 模拟短信发送（控制台输出）
- 同一手机号60秒内只能发送一次

### 2. 验证码登录

**接口**: `POST /api/auth/verify-code`

**认证**: 无需认证

**请求头**:
```
Content-Type: application/json
```

**请求体**:
```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | string | 是 | 手机号 |
| code | string | 是 | 验证码（6位） |

**成功响应** (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": 123,
  "role": "BLIND"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| token | string | JWT token，有效期24小时 |
| userId | number | 用户ID |
| role | string | 用户角色（UNSET/BLIND/VOLUNTEER） |

**失败响应**:
- **400 Bad Request** - 验证码错误或已过期
```json
{
  "error": "验证码错误或已过期"
}
```

- **400 Bad Request** - 参数校验失败
```json
{
  "success": false,
  "code": 400,
  "message": "手机号不能为空"
}
```

**业务逻辑**:
- 验证码正确性校验
- 验证码有效期校验（5分钟）
- 验证码尝试次数校验（最多5次）
- 用户不存在则自动创建
- 生成JWT token
- 返回用户基本信息

### 3. 获取当前用户信息

**接口**: `GET /api/auth/me`

**认证**: 需要JWT token

**请求头**:
```
Authorization: Bearer <JWT_TOKEN>
```

**成功响应** (200 OK):
```json
{
  "userId": 123,
  "phone": "138****8000",
  "role": "BLIND",
  "createdAt": "2026-04-14T10:30:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| userId | number | 用户ID |
| phone | string | 手机号（脱敏显示） |
| role | string | 用户角色 |
| createdAt | string | 注册时间 |

**失败响应**:
- **401 Unauthorized** - 未登录
```json
{
  "error": "未登录"
}
```

## 客服认证（用户名密码登录）

### 4. 客服登录

**接口**: `POST /api/cs/auth/login`

**认证**: 无需认证

**请求头**:
```
Content-Type: application/json
```

**请求体**:
```json
{
  "username": "cs001",
  "password": "password123"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 客服用户名 |
| password | string | 是 | 密码 |

**成功响应** (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "role": "CS"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| token | string | JWT token，携带csRole声明 |
| role | string | 客服角色（CS/ADMIN） |

**失败响应**:
- **400 Bad Request** - 参数为空
```json
{
  "error": "用户名和密码不能为空"
}
```

- **401 Unauthorized** - 用户名或密码错误
```json
{
  "error": "用户名或密码错误"
}
```

**业务逻辑**:
- 从`cs_users`表查询客服用户
- BCrypt密码验证
- 生成JWT token（包含csRole声明）
- 返回token和角色信息

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 参数校验失败、验证码错误 |
| 401 | Unauthorized | 未认证或token无效 |
| 429 | Too Many Requests | 触发限流（10次/分钟） |

## 限流规则

所有认证相关接口受速率限制保护：

- **限流桶**: `auth`
- **限制**: 10次/分钟
- **触发响应**: HTTP 429
```json
{
  "error": "TOO_MANY_REQUESTS",
  "message": "请求过于频繁，请稍后再试",
  "retryAfterSeconds": 60
}
```

## 注意事项

1. **Token存储**: 客户端需妥善保管JWT token，每次请求都需携带在`Authorization`头中
2. **Token过期**: 默认24小时，过期后需重新登录
3. **验证码有效期**: 5分钟，过期需重新获取
4. **验证码尝试次数**: 最多5次，超过需重新获取
5. **手机号脱敏**: 接口返回的手机号会进行脱敏处理（显示前3位+后4位）

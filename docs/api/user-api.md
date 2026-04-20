# 用户API文档

## 概述

用户API提供用户角色设置、用户信息查询、盲人档案管理、志愿者档案管理等功能。所有接口（除角色设置外）都需要JWT token认证。

## 角色管理

### 1. 设置用户角色

**接口**: `POST /api/user/role`

**认证**: 需要JWT token

**请求头**:
```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**请求体**:
```json
{
  "role": "BLIND"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| role | string | 是 | 用户角色（BLIND/VOLUNTEER） |

**成功响应** (200 OK):
```json
{
  "success": true,
  "role": "BLIND"
}
```

**失败响应**:
- **400 Bad Request** - 参数校验失败
```json
{
  "success": false,
  "code": 400,
  "message": "角色不能为空"
}
```

- **409 Conflict** - 角色已设定
```json
{
  "success": false,
  "code": 409,
  "message": "身份已设定，不可修改"
}
```

**业务逻辑**:
- 角色只能设置一次，不可修改
- 设置为BLIND时自动创建BlindProfile记录
- 设置为VOLUNTEER时自动创建VolunteerProfile记录
- 初始注册步骤为STEP_1_BASIC_INFO

## 用户信息管理

### 2. 获取用户信息

**接口**: `GET /api/users/{id}`

**认证**: 需要JWT token

**请求头**:
```
Authorization: Bearer <JWT_TOKEN>
```

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | number | 是 | 用户ID |

**成功响应** (200 OK):
```json
{
  "userId": 123,
  "phone": "138****8000",
  "role": "BLIND",
  "createdAt": "2026-04-14T10:30:00",
  "blindProfile": {
    "name": "张三",
    "runningPace": "6'30\"/km",
    "specialNeeds": "需要扶手"
  }
}
```

**失败响应**:
- **403 Forbidden** - 无权查看其他用户信息
```json
{
  "success": false,
  "code": 403,
  "message": "您只能查看自己的信息"
}
```

- **404 Not Found** - 用户不存在
```json
{
  "error": "用户不存在"
}
```

**业务逻辑**:
- 只能查询自己的用户信息
- 自动附加对应的Profile信息（BlindProfile或VolunteerProfile）
- 手机号脱敏显示

### 3. 注销账号

**接口**: `DELETE /api/users/{id}`

**认证**: 需要JWT token

**请求头**:
```
Authorization: Bearer <JWT_TOKEN>
```

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | number | 是 | 用户ID |

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**失败响应**:
- **403 Forbidden** - 无权删除其他用户
```json
{
  "success": false,
  "code": 403,
  "message": "您只能删除自己的账号"
}
```

**业务逻辑**:
- 只能删除自己的账号
- 软删除：设置deletedAt字段，不物理删除数据
- 删除后无法再登录（JWT验证会失败）

## 盲人档案管理

### 4. 获取盲人档案

**接口**: `GET /api/blind/profile`

**认证**: 需要JWT token（盲人用户）

**请求头**:
```
Authorization: Bearer <JWT_TOKEN>
```

**成功响应** (200 OK):
```json
{
  "name": "张三",
  "runningPace": "6'30\"/km",
  "specialNeeds": "需要扶手，希望志愿者配速不要太快"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| name | string | 姓名 |
| runningPace | string | 跑步配速 |
| specialNeeds | string | 特殊需求 |

**失败响应**:
- **403 Forbidden** - 非盲人用户
```json
{
  "success": false,
  "code": 403,
  "message": "只有盲人用户才能查看此信息"
}
```

### 5. 更新盲人档案

**接口**: `PUT /api/blind/profile`

**认证**: 需要JWT token（盲人用户）

**请求头**:
```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**请求体**:
```json
{
  "name": "张三",
  "runningPace": "6'30\"/km",
  "specialNeeds": "需要扶手"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 否 | 姓名 |
| runningPace | string | 否 | 跑步配速 |
| specialNeeds | string | 否 | 特殊需求 |

**成功响应** (200 OK):
```json
{
  "success": true,
  "data": {
    "name": "张三",
    "runningPace": "6'30\"/km",
    "specialNeeds": "需要扶手"
  }
}
```

## 志愿者档案管理

### 6. 获取志愿者档案

**接口**: `GET /api/volunteer/profile`

**认证**: 需要JWT token（志愿者用户）

**请求头**:
```
Authorization: Bearer <JWT_TOKEN>
```

**成功响应** (200 OK):
```json
{
  "name": "李四",
  "phone": "139****9000",
  "verified": true,
  "registrationStep": "STEP_4_COMPLETED",
  "totalServiceMinutes": 120
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| name | string | 姓名 |
| phone | string | 电话（脱敏） |
| verified | boolean | 是否已认证 |
| registrationStep | string | 注册步骤 |
| totalServiceMinutes | number | 累计服务时长（分钟） |

**失败响应**:
- **403 Forbidden** - 非志愿者用户
```json
{
  "success": false,
  "code": 403,
  "message": "只有志愿者才能查看此信息"
}
```

### 7. 更新志愿者档案

**接口**: `PUT /api/volunteer/profile`

**认证**: 需要JWT token（志愿者用户）

**请求头**:
```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**请求体**:
```json
{
  "name": "李四",
  "runningPace": "5'30\"/km",
  "availableTimeSlots": [
    {
      "dayOfWeek": 1,
      "startHour": 8,
      "endHour": 12
    }
  ]
}
```

**成功响应** (200 OK):
```json
{
  "success": true,
  "data": {
    "name": "李四",
    "phone": "139****9000",
    "verified": true,
    "registrationStep": "STEP_4_COMPLETED"
  }
}
```

### 8. 上传资质证件（旧版）

**接口**: `POST /api/volunteer/verification`

**认证**: 需要JWT token（志愿者用户）

**请求头**:
```
Content-Type: multipart/form-data
Authorization: Bearer <JWT_TOKEN>
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | File | 是 | 证件图片 |

**成功响应** (200 OK):
```json
{
  "success": true,
  "status": "PENDING"
}
```

**注意**: 此接口为旧版认证流程，建议使用新的4步注册流程。

### 9. 获取认证状态（旧版）

**接口**: `GET /api/volunteer/verification/status`

**认证**: 需要JWT token（志愿者用户）

**成功响应** (200 OK):
```json
{
  "status": "APPROVED"
}
```

| 状态 | 说明 |
|------|------|
| NOT_STARTED | 未开始 |
| PENDING | 待审核 |
| APPROVED | 已通过 |
| REJECTED | 未通过 |

### 10. 上报志愿者位置

**接口**: `POST /api/volunteer/location`

**认证**: 需要JWT token（志愿者用户）

**请求头**:
```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**请求体**:
```json
{
  "latitude": 39.9042,
  "longitude": 116.4074,
  "isOnline": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| latitude | number | 是 | 纬度 |
| longitude | number | 是 | 经度 |
| isOnline | boolean | 是 | 是否在线 |

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**业务逻辑**:
- 位置信息双写：Redis（TTL 30秒）+ MySQL
- 用于实时匹配算法
- 30秒内未更新视为离线

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 参数校验失败 |
| 403 | Forbidden | 无权限操作 |
| 404 | Not Found | 资源不存在 |
| 409 | Conflict | 角色已设定 |

## 注意事项

1. **角色设置**: 角色只能设置一次，请谨慎选择
2. **软删除**: 注销账号为软删除，数据仍在数据库中
3. **位置上报**: 志愿者需每30秒上报一次位置以保持在线状态
4. **手机号脱敏**: 所有接口返回的手机号都会脱敏显示
5. **权限控制**: 盲人接口只能由盲人用户调用，志愿者接口只能由志愿者用户调用

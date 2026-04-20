# 管理员API文档

## 概述

管理员API提供志愿者审核、培训课程管理、通知模板管理等功能。所有接口都需要管理员JWT token（csRole=ADMIN）。

## 志愿者审核

### 1. 获取待审核身份证列表

**接口**: `GET /api/admin/volunteers/review/id`

**认证**: 需要JWT token（管理员）

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": [
    {
      "volunteerId": 123,
      "name": "张三",
      "phone": "138****8000",
      "idCardNumber": "110***********1234",
      "idCardName": "张三",
      "idCardFrontUrl": "/uploads/id_front_123.jpg",
      "idCardBackUrl": "/uploads/id_back_123.jpg",
      "submittedAt": "2026-04-15T10:00:00"
    }
  ]
}
```

**注意**: 身份证号脱敏显示（前6位+后4位）

### 2. 审核身份证

**接口**: `POST /api/admin/volunteers/review/id`

**认证**: 需要JWT token（管理员）

**请求体**:
```json
{
  "volunteerId": 123,
  "approved": true,
  "rejectionReason": "照片模糊，请重新上传"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| volunteerId | number | 是 | 志愿者ID |
| approved | boolean | 是 | 是否通过 |
| rejectionReason | string | 否 | 拒绝原因（不通过时必填） |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": "审核完成"
}
```

**业务逻辑**:
- **通过**: 身份证状态→APPROVED，允许人脸验证，发送通知
- **拒绝**: 身份证状态→REJECTED，记录拒绝原因，允许重新提交

### 3. 获取培训统计数据

**接口**: `GET /api/admin/volunteers/training/stats`

**认证**: 需要JWT token（管理员）

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "totalVolunteers": 100,
    "completedTraining": 80,
    "inProgress": 15,
    "notStarted": 5
  }
}
```

## 培训课程管理

### 4. 创建培训课程

**接口**: `POST /api/admin/volunteers/training/courses`

**认证**: 需要JWT token（管理员）

**请求体**:
```json
{
  "title": "紧急情况处理",
  "description": "掌握紧急情况的应对方法和求助流程",
  "durationMinutes": 10,
  "videoUrl": "https://example.com/video.mp4",
  "content": "<h2>应急预案</h2><p>1. 紧急按钮使用...</p>",
  "displayOrder": 3,
  "isActive": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | string | 是 | 课程标题 |
| description | string | 否 | 课程描述 |
| durationMinutes | number | 是 | 课程时长（分钟） |
| videoUrl | string | 否 | 视频URL |
| content | string | 否 | 课程内容（HTML） |
| displayOrder | number | 是 | 显示顺序 |
| isActive | boolean | 是 | 是否启用 |

**成功响应** (201 Created):
```json
{
  "success": true,
  "code": 201,
  "message": "created",
  "data": {
    "id": 4,
    "title": "紧急情况处理",
    "description": "掌握紧急情况的应对方法和求助流程",
    "durationMinutes": 10,
    "displayOrder": 3,
    "isActive": true
  }
}
```

### 5. 更新培训课程

**接口**: `PUT /api/admin/volunteers/training/courses/{id}`

**认证**: 需要JWT token（管理员）

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | number | 是 | 课程ID |

**请求体**: 同创建课程

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "id": 4,
    "title": "紧急情况处理（更新版）",
    "durationMinutes": 15
  }
}
```

### 6. 删除培训课程

**接口**: `DELETE /api/admin/volunteers/training/courses/{id}`

**认证**: 需要JWT token（管理员）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

## 通知模板管理

### 7. 获取所有通知模板

**接口**: `GET /api/admin/notification-templates`

**认证**: 需要JWT token（管理员）

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| eventType | string | 否 | 事件类型过滤 |
| targetRole | string | 否 | 目标角色过滤 |

**成功响应** (200 OK):
```json
[
  {
    "id": 1,
    "eventType": "ORDER_ACCEPTED",
    "targetRole": "BLIND",
    "channel": "WEBSOCKET",
    "templateText": "志愿者{volunteerName}已接单",
    "ttsText": "志愿者{volunteerName}已接单，请注意接听",
    "priority": "NORMAL",
    "isActive": true
  }
]
```

### 8. 更新通知模板

**接口**: `PUT /api/admin/notification-templates/{id}`

**认证**: 需要JWT token（管理员）

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | number | 是 | 模板ID |

**请求体**:
```json
{
  "templateText": "志愿者{volunteerName}已接单，请准备出发",
  "ttsText": "志愿者{volunteerName}已接单",
  "priority": "HIGH",
  "isActive": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateText | string | 否 | 模板文本 |
| ttsText | string | 否 | TTS文本 |
| priority | string | 否 | 优先级（HIGH/NORMAL） |
| isActive | boolean | 否 | 是否启用 |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "eventType": "ORDER_ACCEPTED",
    "templateText": "志愿者{volunteerName}已接单，请准备出发",
    "priority": "HIGH"
  }
}
```

**业务逻辑**:
- 支持占位符替换（如{volunteerName}）
- 更新后清空模板缓存
- 立即生效

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 参数校验失败 |
| 403 | Forbidden | 非管理员无权限 |
| 404 | Not Found | 资源不存在 |

## 注意事项

1. **管理员权限**: 所有接口需要csRole=ADMIN
2. **身份证脱敏**: 列表接口中身份证号脱敏显示
3. **模板缓存**: 更新模板后自动清空缓存
4. **占位符**: 模板支持{volunteerName}等占位符
5. **审核通知**: 审核通过/拒绝后自动发送WebSocket通知

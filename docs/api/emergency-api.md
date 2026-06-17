# 紧急求助API文档

## 概述

紧急求助API提供盲人用户触发紧急求助、志愿者响应、紧急联系人管理等功能。

## 紧急求助流程

```
触发 → 通知志愿者 → 志愿者响应/超时 → 联系人/客服介入 → 解决
PENDING → VOLUNTEER_NOTIFIED → VOLUNTEER_CONFIRMED → CS_HANDLING → RESOLVED
```

## 紧急求助

### 1. 触发紧急求助

**接口**: `POST /api/emergency/trigger`

**认证**: 需要JWT token（盲人或志愿者）

**请求体**:
```json
{
  "orderId": 123,
  "gpsLat": 39.9042,
  "gpsLng": 116.4074
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderId | number | 否 | 订单ID（A1：独立 SOS 可不传；传则校验是否订单参与者） |
| gpsLat | number | 否 | 纬度（A2：可不传，无坐标时短信走"报警110"引导） |
| gpsLng | number | 否 | 经度（同上） |

> **A1/A2/S5 更新**：① 无 `orderId` 可触发独立 SOS（直接升级紧急联系人 + 推客服）；② 紧急联系人短信位置**三级降级**——逆地理编码文字地址（高德 regeo，需配 `AMAP_WEB_KEY`）→ 可读经纬度 → "报警110"引导，**短信禁链接**（违反运营商规定）；③ 若用户**未设置紧急联系人**，盲人会收到"未找到联系人，已转客服"通知，事件转 `CS_HANDLING`。

**成功响应** (200 OK):
```json
{
  "id": 456,
  "orderId": 123,
  "userId": 123,
  "status": "VOLUNTEER_NOTIFIED",
  "triggerType": "BUTTON",
  "gpsLat": 39.9042,
  "gpsLng": 116.4074,
  "createdAt": "2026-04-15T14:30:00"
}
```

**失败响应**:
- **400 Bad Request** - 冷却时间未到
```json
{
  "success": false,
  "code": 400,
  "message": "紧急事件触发过于频繁，请稍后再试"
}
```

**业务逻辑**:
- 冷却时间：60秒（防止误触）
- 校验订单归属（盲人或志愿者）
- WebSocket推送志愿者
- 短信通知盲人用户
- 设置volunteer_timeout_at（30秒）

### 2. 志愿者响应

**接口**: `PUT /api/emergency/{eventId}/volunteer-response`

**认证**: 需要JWT token（志愿者）

**请求体**:
```json
{
  "action": "NEED_HELP"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | string | 是 | FALSE_ALARM/NEED_HELP |

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**业务逻辑**:
- **FALSE_ALARM**: 误触，直接解决
- **NEED_HELP**: 通知紧急联系人，推送客服
- 超时时间：30秒

## 紧急联系人管理

### 3. 获取紧急联系人列表

**接口**: `GET /api/users/{userId}/emergency-contacts`

**认证**: 需要JWT token

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID |

**成功响应** (200 OK):
```json
[
  {
    "id": 1,
    "userId": 123,
    "name": "张三",
    "phone": "13900139000",
    "relationship": "配偶",
    "isPrimary": true
  },
  {
    "id": 2,
    "userId": 123,
    "name": "李四",
    "phone": "13900139001",
    "relationship": "子女",
    "isPrimary": false
  }
]
```

### 4. 添加紧急联系人

**接口**: `POST /api/users/{userId}/emergency-contacts`

**认证**: 需要JWT token

**请求体**:
```json
{
  "name": "王五",
  "phone": "13900139002",
  "relationship": "朋友",
  "isPrimary": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 联系人姓名 |
| phone | string | 是 | 联系电话 |
| relationship | string | 是 | 关系 |
| isPrimary | boolean | 否 | 是否主联系人（默认false） |

**成功响应** (201 Created):
```json
{
  "success": true,
  "code": 201,
  "message": "created",
  "data": {
    "id": 3,
    "name": "王五",
    "phone": "13900139002",
    "relationship": "朋友",
    "isPrimary": false
  }
}
```

**业务逻辑**:
- 每个用户最多5个紧急联系人
- 只能有一个主联系人
- 设置新的主联系人时，自动取消原主联系人

### 5. 更新紧急联系人

**接口**: `PUT /api/users/{userId}/emergency-contacts/{contactId}`

**认证**: 需要JWT token

**请求体**:
```json
{
  "name": "王五",
  "phone": "13900139002",
  "relationship": "朋友",
  "isPrimary": true
}
```

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "id": 3,
    "name": "王五",
    "phone": "13900139002",
    "relationship": "朋友",
    "isPrimary": true
  }
}
```

### 6. 删除紧急联系人

**接口**: `DELETE /api/users/{userId}/emergency-contacts/{contactId}`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
{
  "success": true
}
```

### 7. 设置主联系人

**接口**: `PUT /api/users/{userId}/emergency-contacts/{contactId}/set-primary`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**业务逻辑**: 取消其他联系人主联系人标记，设置指定联系人为主联系人

## 客服处理紧急事件

### 8. 获取紧急事件列表

**接口**: `GET /api/cs/emergency-events`

**认证**: 需要JWT token（客服）

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | string | 否 | 事件状态过滤 |

**成功响应** (200 OK):
```json
[
  {
    "id": 456,
    "orderId": 123,
    "userId": 123,
    "status": "VOLUNTEER_CONFIRMED",
    "triggerType": "BUTTON",
    "gpsLat": 39.9042,
    "gpsLng": 116.4074,
    "createdAt": "2026-04-15T14:30:00"
  }
]
```

### 9. 客服接受事件

**接口**: `PUT /api/cs/emergency-events/{id}/accept`

**认证**: 需要JWT token（客服）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态**: 任意 → CS_HANDLING

### 10. 通知紧急联系人

**接口**: `PUT /api/cs/emergency-events/{id}/notify-contact`

**认证**: 需要JWT token（客服）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**业务逻辑**: 按优先级通知紧急联系人，记录通知历史

### 11. 解决事件

**接口**: `PUT /api/cs/emergency-events/{id}/resolve`

**认证**: 需要JWT token（客服）

**请求体**:
```json
{
  "resolutionNote": "已联系志愿者，确认安全"
}
```

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态**: CS_HANDLING → RESOLVED

### 12. 标记为误触

**接口**: `PUT /api/cs/emergency-events/{id}/false-alarm`

**认证**: 需要JWT token（客服）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态**: 任意 → FALSE_ALARM

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 冷却时间未到、参数错误 |
| 403 | Forbidden | 无权限操作 |
| 404 | Not Found | 资源不存在 |

## 定时任务

### 志愿者超时检测
- **触发**: 每10秒
- **条件**: status=VOLUNTEER_NOTIFIED 且 volunteer_timeout_at < NOW()
- **处理**: 自动通知紧急联系人，推送客服

## 注意事项

1. **冷却时间**: 60秒内只能触发一次
2. **志愿者超时**: 30秒内未响应自动升级
3. **联系人数量**: 每个用户最多5个紧急联系人
4. **主联系人**: 只能有一个主联系人
5. **订单归属**: 只能触发自己订单的紧急求助

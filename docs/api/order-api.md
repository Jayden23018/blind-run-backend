# 订单API文档

## 概述

订单API管理订单的完整生命周期，包括创建、接单、状态流转、评价等功能。

## 订单状态流转

```
PENDING_MATCH → PENDING_ACCEPT → IN_PROGRESS → DRIVER_EN_ROUTE → DRIVER_ARRIVED → COMPLETED
     ↓              ↓               ↓              ↓                ↓
  CANCELLED    REMATCHING      CANCELLED     REMATCHING      REMATCHING
```

## 订单管理

### 1. 创建订单

**接口**: `POST /api/orders`

**认证**: 需要JWT token（盲人用户）

**请求体**:
```json
{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-15T14:00:00",
  "plannedEndTime": "2026-04-15T15:00:00"
}
```

**成功响应** (201 Created):
```json
{
  "id": 123,
  "status": "PENDING_MATCH",
  "message": "订单已提交，正在匹配志愿者"
}
```

**失败响应**:
- **400 Bad Request** - 时间不合理
```json
{
  "success": false,
  "code": 400,
  "message": "计划结束时间必须晚于开始时间"
}
```

- **409 Conflict** - 有进行中的订单
```json
{
  "success": false,
  "code": 409,
  "message": "您有进行中的订单，请完成后再下单"
}
```

**业务逻辑**:
- 校验紧急联系人已设置
- 订单状态：PENDING_MATCH
- 触发MatchingService匹配志愿者
- 5分钟无人接单提醒盲人用户

### 2. 查看可用订单

**接口**: `GET /api/orders/available`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
[
  {
    "orderId": 123,
    "distanceKm": 2.5,
    "startAddress": "朝阳公园南门",
    "plannedStartTime": "2026-04-15T14:00:00",
    "plannedEndTime": "2026-04-15T15:00:00"
  }
]
```

**业务逻辑**:
- 返回10km内的订单
- 志愿者需已上报位置
- 按距离排序

### 3. 响应派单（新接口，推荐使用）

**接口**: `POST /api/orders/{id}/respond`

**认证**: 需要JWT token（志愿者）

**说明**: 串行派单专用。系统按评分逐一推送志愿者，被推送的志愿者在30秒内用此接口接单或跳过。

**请求体**:
```json
{
  "action": "ACCEPT"
}
```
或
```json
{
  "action": "DECLINE"
}
```

**成功响应** (200 OK):
```json
{
  "success": true,
  "orderId": 123
}
```

**失败响应**:
- **403 Forbidden** - 未完成注册或非当前派单志愿者
- **409 Conflict** - 订单状态已改变（已被他人接单或已取消）

**业务逻辑**:
- 仅当前被派单的志愿者可操作（其他志愿者会收到403）
- ACCEPT：乐观锁保护，自动重试3次，PENDING_MATCH → PENDING_ACCEPT
- DECLINE：跳过，派单队列推进到下一个候选人

---

### 4. 接单（已废弃，兼容保留）

> ⚠️ **已废弃**: 请使用 `POST /api/orders/{id}/respond`。**B2 修复后此接口已复用 /respond 的派单归属校验（行为与 /respond 一致）**，仅保留兼容旧前端。

**接口**: `POST /api/orders/{id}/accept`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true,
  "orderId": 123
}
```

**业务逻辑**（B2 修复后，与 `/respond` 行为一致）:
- 复用派单归属校验：仅当前被派单的志愿者可接单（非当前派单对象→409）
- 入口校验志愿者资质（未完成注册→403 "请先完成志愿者注册流程"）
- ⚠️ **行为变化**：接单后状态为 `PENDING_ACCEPT`，经 `@Async` 事件**异步推进**到 `IN_PROGRESS`（不再是同步 `IN_PROGRESS`）；前端如需立即感知请轮询订单状态或监听 WebSocket
- 接单失败返回 409（`OrderStatusException`）

### 5. 拒单（已废弃，兼容保留）

> ⚠️ **已废弃**: 请使用 `POST /api/orders/{id}/respond {"action":"DECLINE"}`

**接口**: `POST /api/orders/{id}/reject`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

### 6. 志愿者出发

**接口**: `POST /api/orders/{id}/en-route`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态**: IN_PROGRESS → DRIVER_EN_ROUTE

### 7. 志愿者到达

**接口**: `POST /api/orders/{id}/arrived`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态**: DRIVER_EN_ROUTE → DRIVER_ARRIVED

### 8. 完成服务

**接口**: `POST /api/orders/{id}/finish`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true,
  "orderId": 123
}
```

**状态**: DRIVER_ARRIVED → COMPLETED

### 9. 取消订单

**接口**: `POST /api/orders/{id}/cancel`

**认证**: 需要JWT token（盲人或志愿者）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**状态流转**:
- 志愿者在PENDING_ACCEPT / DRIVER_EN_ROUTE / DRIVER_ARRIVED取消 → REMATCHING
- 志愿者在IN_PROGRESS取消 → CANCELLED（记录爽约）
- 盲人用户取消 → CANCELLED

### 10. 继续等待匹配

**接口**: `PUT /api/orders/{id}/keep-waiting`

**认证**: 需要JWT token（盲人用户）

**成功响应** (200 OK):
```json
{
  "success": true
}
```

**业务逻辑**: 刷新匹配超时时间，继续等待志愿者

### 10. 查询订单详情

**接口**: `GET /api/orders/{id}`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
{
  "orderId": 123,
  "status": "IN_PROGRESS",
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-15T14:00:00",
  "plannedEndTime": "2026-04-15T15:00:00",
  "volunteerPhone": "139****9000",
  "acceptedAt": "2026-04-15T13:55:00",
  "createdAt": "2026-04-15T13:50:00"
}
```

### 11. 查询我的订单

**接口**: `GET /api/orders/mine`

**认证**: 需要JWT token

**查询参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| role | string | 否 | BLIND/VOLUNTEER（默认从用户信息获取） |
| status | string | 否 | 订单状态过滤 |
| page | number | 否 | 页码（默认0） |
| size | number | 否 | 每页数量（默认10） |

**成功响应** (200 OK):
```json
{
  "content": [
    {
      "orderId": 123,
      "status": "COMPLETED",
      "startAddress": "朝阳公园南门",
      "plannedStartTime": "2026-04-15T14:00:00",
      "plannedEndTime": "2026-04-15T15:00:00",
      "createdAt": "2026-04-15T13:50:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 10,
  "number": 0
}
```

### 12. 查询订单状态日志

**接口**: `GET /api/orders/{id}/status-logs`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
[
  {
    "id": 1,
    "orderId": 123,
    "fromStatus": null,
    "toStatus": "PENDING_MATCH",
    "remark": "创建订单",
    "createdAt": "2026-04-15T13:50:00"
  },
  {
    "id": 2,
    "orderId": 123,
    "fromStatus": "PENDING_MATCH",
    "toStatus": "IN_PROGRESS",
    "remark": "志愿者接单",
    "createdAt": "2026-04-15T13:55:00"
  }
]
```

### 13. 提交评价

**接口**: `POST /api/orders/{id}/review`

**认证**: 需要JWT token（盲人或志愿者）

**请求体**:
```json
{
  "rating": 5,
  "comment": "志愿者很专业，服务态度很好"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| rating | number | 是 | 评分（1-5星） |
| comment | string | 否 | 评论内容 |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 201,
  "message": "created",
  "data": {
    "id": 456,
    "rating": 5,
    "comment": "志愿者很专业，服务态度很好",
    "createdAt": "2026-04-15T15:10:00"
  }
}
```

### 14. 查询订单评价

**接口**: `GET /api/orders/{id}/reviews`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
[
  {
    "id": 456,
    "orderId": 123,
    "reviewerRole": "BLIND",
    "rating": 5,
    "comment": "志愿者很专业",
    "createdAt": "2026-04-15T15:10:00"
  }
]
```

## 隐私号通话

### 15. 发起通话

**接口**: `POST /api/orders/{orderId}/call/initiate`

**认证**: 需要JWT token

**请求体**:
```json
{
  "callerRole": "VOLUNTEER"
}
```

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "callId": 789,
    "virtualNumber": "17012345678",
    "status": "CONNECTED"
  }
}
```

**失败响应**:
- **400 Bad Request** - 隐私号未启用
```json
{
  "success": false,
  "code": 400,
  "message": "隐私号功能未启用"
}
```

### 16. 查询通话记录

**接口**: `GET /api/orders/{orderId}/call/records`

**认证**: 需要JWT token

**成功响应** (200 OK):
```json
[
  {
    "id": 789,
    "callerRole": "VOLUNTEER",
    "calleeRole": "BLIND",
    "virtualNumber": "17012345678",
    "status": "CONNECTED",
    "createdAt": "2026-04-15T14:05:00"
  }
]
```

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 参数错误、时间不合理 |
| 403 | Forbidden | 无权限操作、未完成注册 |
| 404 | Not Found | 订单不存在 |
| 409 | Conflict | 订单已被接、状态冲突 |

## 注意事项

1. **重复订单**: 不能有多个进行中的订单
2. **紧急联系人**: 下单前必须设置紧急联系人
3. **乐观锁**: 防止并发接单冲突
4. **状态流转**: 必须按状态机顺序流转
5. **隐私号**: 需配置阿里云隐私号，否则返回模拟数据

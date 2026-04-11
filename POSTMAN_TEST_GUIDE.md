# Postman 全功能测试指南

> 先启动服务：`./gradlew bootRun`（端口 8081）
>
> **Postman 变量设置建议**：在 Collection 级别添加 Variables：
> - `base_url` = `http://127.0.0.1:8081`
> - `blind_token`、`volunteer_token`、`blind_id`、`volunteer_id`、`order_id`、`event_id` 等在测试过程中逐步填入
>
> **验证码获取**：发送验证码后查看服务控制台日志，搜索 `【模拟短信】` 即可看到验证码

---

## Part 1：注册两个用户

### Step 1：发送验证码（盲人）
```
POST {{base_url}}/api/auth/send-code
Content-Type: application/json

{
  "phone": "13800000001"
}
```
**预期返回**：`{"success": true, "message": "验证码已发送"}`
**注意**：查看服务控制台，找到 `【模拟短信】发送至 13800000001: 验证码: XXXXXX`，记下验证码

### Step 2：验证码登录（盲人）
```
POST {{base_url}}/api/auth/verify-code
Content-Type: application/json

{
  "phone": "13800000001",
  "code": "这里填上一步获取的验证码"
}
```
**预期返回**：
```json
{
  "token": "eyJhbGciOi...",
  "userId": 1,
  "role": "UNSET"
}
```
**操作**：记下 `token` → 设为 `{{blind_token}}`，记下 `userId` → 设为 `{{blind_id}}`

### Step 3：设置角色为 BLIND
```
POST {{base_url}}/api/user/role
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "role": "BLIND"
}
```
**预期返回**：`{"success": true, "message": "角色设置成功", "role": "BLIND"}`

### Step 4：发送验证码（志愿者）
```
POST {{base_url}}/api/auth/send-code
Content-Type: application/json

{
  "phone": "13800000002"
}
```
**预期返回**：`{"success": true, "message": "验证码已发送"}`
**注意**：同样查看控制台获取验证码

### Step 5：验证码登录（志愿者）
```
POST {{base_url}}/api/auth/verify-code
Content-Type: application/json

{
  "phone": "13800000002",
  "code": "填入验证码"
}
```
**预期返回**：`{"token": "...", "userId": 2, "role": "UNSET"}`
**操作**：记下 → 设为 `{{volunteer_token}}`，`{{volunteer_id}}`

### Step 6：设置角色为 VOLUNTEER
```
POST {{base_url}}/api/user/role
Authorization: Bearer {{volunteer_token}}
Content-Type: application/json

{
  "role": "VOLUNTEER"
}
```
**预期返回**：`{"success": true, "message": "角色设置成功", "role": "VOLUNTEER"}`

---

## Part 2：盲人资料 + 紧急联系人

### Step 7：查看盲人资料（初始为空）
```
GET {{base_url}}/api/blind/profile
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": {"name": null, "runningPace": null, ...}}`

### Step 8：更新盲人资料
```
PUT {{base_url}}/api/blind/profile
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "name": "张三",
  "runningPace": "6:00",
  "specialNeeds": "需要语音引导"
}
```
**预期返回**：`{"success": true, "data": {"name": "张三", "runningPace": "6:00", ...}}`

### Step 9：添加紧急联系人 1（主要联系人）
```
POST {{base_url}}/api/users/{{blind_id}}/emergency-contacts
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "name": "李四",
  "phone": "13900001111",
  "relationship": "配偶",
  "isPrimary": true
}
```
**预期返回**：`{"success": true, "data": {"id": 1, "name": "李四", "isPrimary": true, ...}}`
**注意**：记下联系人 ID → 设为 `{{contact_1_id}}`

### Step 10：添加紧急联系人 2
```
POST {{base_url}}/api/users/{{blind_id}}/emergency-contacts
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "name": "王五",
  "phone": "13900002222",
  "relationship": "父亲",
  "isPrimary": false
}
```
**预期返回**：`{"success": true, "data": {"id": 2, "name": "王五", "isPrimary": false, ...}}`
**操作**：记下 → 设为 `{{contact_2_id}}`

### Step 11：查看所有紧急联系人
```
GET {{base_url}}/api/users/{{blind_id}}/emergency-contacts
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": [联系人1, 联系人2]}`

### Step 12：更新紧急联系人 2
```
PUT {{base_url}}/api/users/{{blind_id}}/emergency-contacts/{{contact_2_id}}
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "name": "王五",
  "phone": "13900003333",
  "relationship": "母亲"
}
```
**预期返回**：`{"success": true, "data": {"phone": "13900003333", ...}}`

### Step 13：设置联系人 2 为主要联系人
```
PUT {{base_url}}/api/users/{{blind_id}}/emergency-contacts/{{contact_2_id}}/set-primary
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "message": "已设为主要联系人"}`

### Step 14：删除紧急联系人（可选）
```
DELETE {{base_url}}/api/users/{{blind_id}}/emergency-contacts/{{contact_2_id}}
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true}`
**注意**：如果删除了主要联系人，记得重新设一个。测试完建议重新添加回来，后续下单需要至少 1 个联系人

---

## Part 3：志愿者端配置

### Step 15：查看志愿者资料（初始为空）
```
GET {{base_url}}/api/volunteer/profile
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "data": {"name": null, "verified": false, ...}}`

### Step 16：更新志愿者资料
```
PUT {{base_url}}/api/volunteer/profile
Authorization: Bearer {{volunteer_token}}
Content-Type: application/json

{
  "name": "赵六",
  "availableTimeSlots": [
    {
      "dayOfWeek": "SATURDAY",
      "startTime": "08:00",
      "endTime": "12:00"
    },
    {
      "dayOfWeek": "SUNDAY",
      "startTime": "09:00",
      "endTime": "11:00"
    }
  ]
}
```
**预期返回**：`{"success": true, "data": {"name": "赵六", ...}}`

### Step 17：提交志愿者认证（multipart 文件上传）
```
POST {{base_url}}/api/volunteer/verification
Authorization: Bearer {{volunteer_token}}
Content-Type: multipart/form-data
```
- 选择 Body → form-data
- 添加 key: `file`（类型选 File），选择一张图片文件
- 无需其他参数

**预期返回**：`{"success": true, "message": "认证资料已提交"}`
**注意**：当前版本自动审批通过

### Step 18：查看认证状态
```
GET {{base_url}}/api/volunteer/verification/status
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "data": {"status": "APPROVED"}}`

### Step 19：上报志愿者位置（必须在接单附近）
```
POST {{base_url}}/api/volunteer/location
Authorization: Bearer {{volunteer_token}}
Content-Type: application/json

{
  "latitude": 39.9042,
  "longitude": 116.4074,
  "isOnline": true
}
```
**预期返回**：`{"success": true, "message": "位置已更新"}`
**注意**：这个坐标要和下单时的起跑点距离 < 10km 才能匹配到

---

## Part 4：完整订单流程

### Step 20：盲人创建订单
```
POST {{base_url}}/api/orders
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-12T09:00:00",
  "plannedEndTime": "2026-04-12T10:00:00"
}
```
**预期返回**：
```json
{
  "success": true,
  "data": {
    "id": 1001,
    "status": "PENDING_MATCH",
    "startAddress": "朝阳公园南门",
    ...
  }
}
```
**操作**：记下订单 ID → 设为 `{{order_id}}`
**注意**：`plannedStartTime` 必须是未来时间

### Step 21：志愿者查看可用订单
```
GET {{base_url}}/api/orders/available
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "data": [{"id": 1001, "status": "PENDING_MATCH", ...}]}`
**注意**：需要志愿者位置在订单附近（<10km）才能看到

### Step 22：盲人查看自己的订单
```
GET {{base_url}}/api/orders/mine
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": [{"id": 1001, "status": "PENDING_MATCH", ...}]}`

### Step 23：志愿者接单
```
POST {{base_url}}/api/orders/{{order_id}}/accept
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "message": "已接单"}`
**注意**：订单状态变为 IN_PROGRESS（自动跳过 PENDING_ACCEPT）

### Step 24：查看订单详情
```
GET {{base_url}}/api/orders/{{order_id}}
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": {"id": 1001, "status": "IN_PROGRESS", "volunteer": {...}, ...}}`

### Step 25：志愿者出发
```
POST {{base_url}}/api/orders/{{order_id}}/en-route
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "message": "志愿者已出发"}`
**注意**：状态变为 DRIVER_EN_ROUTE

### Step 26：志愿者到达
```
POST {{base_url}}/api/orders/{{order_id}}/arrived
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "message": "志愿者已到达"}`
**注意**：状态变为 DRIVER_ARRIVED

### Step 27：盲人上报位置（邻近感知触发）
```
POST {{base_url}}/api/blind/location
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "latitude": 39.9045,
  "longitude": 116.4078
}
```
**预期返回**：`{"success": true, "message": "位置已更新"}`
**注意**：如果盲人与志愿者距离 < 100m，会触发邻近感知 WebSocket 推送（需 WebSocket 客户端连接才能看到）

### Step 28：结束订单（完成服务）
```
POST {{base_url}}/api/orders/{{order_id}}/finish
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "message": "订单已完成"}`
**注意**：状态变为 COMPLETED

### Step 29：查看订单状态日志
```
GET {{base_url}}/api/orders/{{order_id}}/status-logs
Authorization: Bearer {{blind_token}}
```
**预期返回**：
```json
{
  "success": true,
  "data": [
    {"fromStatus": null, "toStatus": "PENDING_MATCH", "createdAt": "..."},
    {"fromStatus": "PENDING_MATCH", "toStatus": "IN_PROGRESS", "createdAt": "..."},
    {"fromStatus": "IN_PROGRESS", "toStatus": "DRIVER_EN_ROUTE", "createdAt": "..."},
    {"fromStatus": "DRIVER_EN_ROUTE", "toStatus": "DRIVER_ARRIVED", "createdAt": "..."},
    {"fromStatus": "DRIVER_ARRIVED", "toStatus": "COMPLETED", "createdAt": "..."}
  ]
}
```

---

## Part 5：通话 + 评价

### Step 30：盲人发起通话
```
POST {{base_url}}/api/orders/{{order_id}}/call/initiate
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "callerRole": "BLIND_USER"
}
```
**预期返回**：`{"success": true, "data": {"callRecordId": 1, "status": "INITIATED", ...}}`
**注意**：当前是 Stub 实现，不会真正拨打电话

### Step 31：查看通话记录
```
GET {{base_url}}/api/orders/{{order_id}}/call/records
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": [{"id": 1, "callerRole": "BLIND_USER", "status": "INITIATED", ...}]}`

### Step 32：盲人评价订单
```
POST {{base_url}}/api/orders/{{order_id}}/review
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "rating": 5,
  "comment": "志愿者非常专业，全程陪伴很安心"
}
```
**预期返回**：`{"success": true, "data": {"rating": 5, "comment": "...", ...}}`

### Step 33：查看订单评价
```
GET {{base_url}}/api/orders/{{order_id}}/reviews
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": [{"rating": 5, "comment": "...", "reviewer": {...}, ...}]}`

---

## Part 6：紧急事件流程 A — 志愿者确认需要帮助

> 需要新建一个订单来测试紧急事件

### Step 34：盲人创建订单 2
```
POST {{base_url}}/api/orders
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-12T14:00:00",
  "plannedEndTime": "2026-04-12T15:00:00"
}
```
**操作**：记下新订单 ID → 设为 `{{order_2_id}}`

### Step 35：志愿者再次上报位置
```
POST {{base_url}}/api/volunteer/location
Authorization: Bearer {{volunteer_token}}
Content-Type: application/json

{
  "latitude": 39.9042,
  "longitude": 116.4074,
  "isOnline": true
}
```

### Step 36：志愿者接单 2
```
POST {{base_url}}/api/orders/{{order_2_id}}/accept
Authorization: Bearer {{volunteer_token}}
```

### Step 37：盲人触发紧急事件
```
POST {{base_url}}/api/emergency/trigger
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "orderId": {{order_2_id}},
  "gpsLat": 39.9042,
  "gpsLng": 116.4074
}
```
**预期返回**：
```json
{
  "success": true,
  "eventId": 1,
  "status": "VOLUNTEER_NOTIFIED"
}
```
**操作**：记下 eventId → 设为 `{{event_id}}`
**注意**：服务控制台会打印 `【模拟短信】发送至 userId=...` 和紧急事件日志

### Step 38：志愿者确认需要帮助（30秒内）
```
PUT {{base_url}}/api/emergency/{{event_id}}/volunteer-response
Authorization: Bearer {{volunteer_token}}
Content-Type: application/json

action=NEED_HELP
```
**注意**：这个用 query param，不是 body。完整 URL：
`{{base_url}}/api/emergency/{{event_id}}/volunteer-response?action=NEED_HELP`

**预期返回**：`{"success": true, "eventId": 1, "action": "NEED_HELP"}`
**注意**：状态变为 VOLUNTEER_CONFIRMED → 自动通知紧急联系人

### Step 39：客服获取待处理事件
```
GET {{base_url}}/api/cs/emergency-events
Authorization: Bearer {{blind_token}}
```
**注意**：目前 CS 接口没有独立的客服认证，先用任意 token 访问
**预期返回**：`[{"id": 1, "status": "CONTACT_NOTIFIED", ...}]`

### Step 40：客服接手
```
PUT {{base_url}}/api/cs/emergency-events/{{event_id}}/accept?csUserId=999
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true}`

### Step 41：客服通知紧急联系人
```
PUT {{base_url}}/api/cs/emergency-events/{{event_id}}/notify-contact?csUserId=999
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true}`
**注意**：控制台会打印 `【模拟短信】发送至 13900003333: 紧急通知...`

### Step 42：客服标记已解决
```
PUT {{base_url}}/api/cs/emergency-events/{{event_id}}/resolve?csUserId=999&notes=已确认安全
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true}`

---

## Part 7：紧急事件流程 B — 志愿者确认误触

> 再建一个订单

### Step 43：盲人创建订单 3 + 志愿者接单
```
（同 Step 34-36，使用不同的时间）
```
**操作**：记为 `{{order_3_id}}`

### Step 44：盲人触发紧急事件
```
POST {{base_url}}/api/emergency/trigger
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "orderId": {{order_3_id}},
  "gpsLat": 39.9042,
  "gpsLng": 116.4074
}
```
**操作**：记下新 eventId → 设为 `{{event_2_id}}`

### Step 45：志愿者确认误触
```
PUT {{base_url}}/api/emergency/{{event_2_id}}/volunteer-response?action=FALSE_ALARM
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "eventId": ..., "action": "FALSE_ALARM"}`
**注意**：事件直接标记为 FALSE_ALARM，不会通知紧急联系人

---

## Part 8：紧急事件流程 C — 志愿者超时

> 再建一个订单

### Step 46：盲人创建订单 4 + 志愿者接单
```
（同上，使用不同的时间）
```
**操作**：记为 `{{order_4_id}}`

### Step 47：盲人触发紧急事件
```
POST {{base_url}}/api/emergency/trigger
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "orderId": {{order_4_id}},
  "gpsLat": 39.9042,
  "gpsLng": 116.4074
}
```

### Step 48：等待 30 秒（不操作）
**什么都不做，等 30 秒**。服务端会自动触发超时处理。

### Step 49：查看事件状态（应为 CONTACT_NOTIFIED）
```
GET {{base_url}}/api/cs/emergency-events
Authorization: Bearer {{blind_token}}
```
**预期返回**：应看到该事件状态为 `CONTACT_NOTIFIED`（超时 → 视为严重 → 自动通知紧急联系人）

### Step 50：客服标记误触
```
PUT {{base_url}}/api/cs/emergency-events/{{event_3_id}}/false-alarm?csUserId=999
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true}`

---

## Part 9：订单取消

### Step 51：盲人创建订单 5（用于测试取消）
```
POST {{base_url}}/api/orders
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-13T09:00:00",
  "plannedEndTime": "2026-04-13T10:00:00"
}
```
**操作**：记为 `{{order_5_id}}`

### Step 52：盲人取消订单
```
POST {{base_url}}/api/orders/{{order_5_id}}/cancel
Authorization: Bearer {{blind_token}}
Content-Type: application/json

{
  "reason": "临时有事"
}
```
**预期返回**：`{"success": true, "message": "订单已取消"}`

### Step 53：志愿者拒绝订单（需先创建并接单再测试）
先创建订单 6 → 志愿者上报位置 → 接单：
```
POST {{base_url}}/api/orders/{{order_6_id}}/reject
Authorization: Bearer {{volunteer_token}}
```
**注意**：reject 需要订单状态为 PENDING_MATCH 或 PENDING_ACCEPT

---

## Part 10：用户管理

### Step 54：查看用户信息
```
GET {{base_url}}/api/users/{{blind_id}}
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "data": {"id": 1, "phone": "138****0001", "role": "BLIND", ...}}`
**注意**：手机号会被脱敏显示

### Step 55：查看当前登录用户信息
```
GET {{base_url}}/api/auth/me
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"userId": 1, "phone": "13800000001", "role": "BLIND"}`

### Step 56：志愿者查看自己的订单
```
GET {{base_url}}/api/orders/mine
Authorization: Bearer {{volunteer_token}}
```
**预期返回**：`{"success": true, "data": [...]}`

### Step 57：盲人查看自己的订单（带角色参数）
```
GET {{base_url}}/api/orders/mine?role=BLIND
Authorization: Bearer {{blind_token}}
```

### Step 58：软删除用户（可选，测试完后不要删主要账号）
```
DELETE {{base_url}}/api/users/{{blind_id}}
Authorization: Bearer {{blind_token}}
```
**预期返回**：`{"success": true, "message": "账号已注销"}`
**注意**：JWT 中的 userId 必须和路径中的 {id} 一致；此操作不可逆（软删除）

---

## 端点覆盖清单（38 个接口）

| # | 接口 | 测试步骤 |
|---|------|----------|
| 1 | POST /api/auth/send-code | Step 1, 4 |
| 2 | POST /api/auth/verify-code | Step 2, 5 |
| 3 | GET /api/auth/me | Step 55 |
| 4 | POST /api/user/role | Step 3, 6 |
| 5 | GET /api/blind/profile | Step 7 |
| 6 | PUT /api/blind/profile | Step 8 |
| 7 | POST /api/blind/location | Step 27 |
| 8 | POST /api/users/{id}/emergency-contacts | Step 9, 10 |
| 9 | GET /api/users/{id}/emergency-contacts | Step 11 |
| 10 | PUT /api/users/{id}/emergency-contacts/{id} | Step 12 |
| 11 | DELETE /api/users/{id}/emergency-contacts/{id} | Step 14 |
| 12 | PUT /api/users/{id}/emergency-contacts/{id}/set-primary | Step 13 |
| 13 | GET /api/volunteer/profile | Step 15 |
| 14 | PUT /api/volunteer/profile | Step 16 |
| 15 | POST /api/volunteer/verification | Step 17 |
| 16 | GET /api/volunteer/verification/status | Step 18 |
| 17 | POST /api/volunteer/location | Step 19, 35 |
| 18 | POST /api/orders | Step 20, 34, 43, 46, 51 |
| 19 | GET /api/orders/available | Step 21 |
| 20 | GET /api/orders/{id} | Step 24 |
| 21 | GET /api/orders/mine | Step 22, 56, 57 |
| 22 | POST /api/orders/{id}/accept | Step 23, 36 |
| 23 | POST /api/orders/{id}/reject | Step 53 |
| 24 | POST /api/orders/{id}/en-route | Step 25 |
| 25 | POST /api/orders/{id}/arrived | Step 26 |
| 26 | POST /api/orders/{id}/finish | Step 28 |
| 27 | POST /api/orders/{id}/cancel | Step 52 |
| 28 | GET /api/orders/{id}/status-logs | Step 29 |
| 29 | POST /api/emergency/trigger | Step 37, 44, 47 |
| 30 | PUT /api/emergency/{id}/volunteer-response | Step 38, 45 |
| 31 | GET /api/cs/emergency-events | Step 39, 49 |
| 32 | PUT /api/cs/emergency-events/{id}/accept | Step 40 |
| 33 | PUT /api/cs/emergency-events/{id}/notify-contact | Step 41 |
| 34 | PUT /api/cs/emergency-events/{id}/resolve | Step 42 |
| 35 | PUT /api/cs/emergency-events/{id}/false-alarm | Step 50 |
| 36 | POST /api/orders/{id}/call/initiate | Step 30 |
| 37 | GET /api/orders/{id}/call/records | Step 31 |
| 38 | POST /api/orders/{id}/review | Step 32 |
| 39 | GET /api/orders/{id}/reviews | Step 33 |
| 40 | GET /api/users/{id} | Step 54 |
| 41 | DELETE /api/users/{id} | Step 58 |

---

## 常见问题

**Q: 返回 401？**
A: Token 过期或未设置 Authorization header，确认格式为 `Bearer {{token}}`

**Q: 下单返回 "请先设置紧急联系人"？**
A: 需要先添加至少 1 个紧急联系人（Step 9）

**Q: 志愿者看不到可用订单？**
A: 必须先上报位置（Step 19），且坐标与订单起跑点距离 < 10km

**Q: 紧急事件触发返回 "触发过于频繁"？**
A: 有 60 秒冷却期，等一下再试

**Q: macOS curl 失败？**
A: 用 `127.0.0.1` 而不是 `localhost`

# Blind Running Companion (助盲跑) API Reference

**Version:** 4.0.0
**Base URL:** `http://localhost:8081`
**Content Type:** `application/json`
**Character Set:** `UTF-8`

## Table of Contents

1. [Authentication](#authentication)
2. [CS Authentication](#cs-authentication)
3. [Role Management](#role-management)
4. [User Management](#user-management)
5. [Blind Profile](#blind-profile)
6. [Blind Location](#blind-location)
7. [Emergency Contacts](#emergency-contacts)
8. [Volunteer Profile](#volunteer-profile)
9. [Volunteer Verification](#volunteer-verification)
10. [Volunteer Location](#volunteer-location)
11. [Order Management](#order-management)
12. [Order Status Logs](#order-status-logs)
13. [Emergency Events](#emergency-events)
14. [CS Emergency Operations](#cs-emergency-operations)
15. [Call Records](#call-records)
16. [Reviews](#reviews)
17. [Error Responses](#error-responses)
18. [WebSocket Protocol](#websocket-protocol)
19. [Data Types and Enums](#data-types-and-enums)

---

## Authentication

### Send Verification Code

Send a 6-digit SMS verification code to the user's phone number.

**Endpoint:** `POST /api/auth/send-code`  
**Authentication:** None (Public)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| phone | string | Yes | Mobile phone number |

```json
{ "phone": "13800138000" }
```

**Response:** `200 OK`
```json
{ "success": true, "message": "验证码已发送" }
```

**Notes:** Verification code stored in Redis (`sms:code:{phone}`) with 5 min TTL. In development, check logs for `【模拟短信】` to extract the code.

---

### Verify Code and Login

**Endpoint:** `POST /api/auth/verify-code`  
**Authentication:** None (Public)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| phone | string | Yes | Mobile phone number |
| code | string | Yes | 6-digit verification code |

```json
{ "phone": "13800138000", "code": "123456" }
```

**Response:** `200 OK`
```json
{ "token": "eyJhbGciOi...", "userId": 1, "role": "UNSET" }
```

---

### Get Current User

**Endpoint:** `GET /api/auth/me`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "userId": 1, "phone": "138****8000", "role": "BLIND", "createdAt": "2026-04-10T10:30:00" }
```

---

## CS Authentication

Customer service users use a separate auth system (username + password) with an independent `cs_users` table.

### CS Login

**Endpoint:** `POST /api/cs/auth/login`
**Authentication:** None (Public)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| username | string | Yes | CS account username |
| password | string | Yes | CS account password |

```json
{ "username": "admin", "password": "admin123" }
```

**Response:** `200 OK`
```json
{ "token": "eyJhbGciOi...", "role": "ADMIN" }
```

**Error:** `401` — Invalid username or password.

**Notes:**
- CS JWT tokens carry a `csRole` claim (`CS` or `ADMIN`) in the payload.
- The `JwtFilter` stores this claim in `Authentication.details` for downstream authorization.
- Default admin account: `admin` / `admin123` (seeded via `data.sql`).
- CS tokens are validated by the same `JwtUtil` and `JwtFilter` used by regular users.

---

## Role Management

### Set User Role (one-time)

**Endpoint:** `POST /api/user/role`  
**Authentication:** Required (JWT)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| role | string | Yes | `BLIND` or `VOLUNTEER` |

```json
{ "role": "BLIND" }
```

**Response:** `200 OK`
```json
{ "success": true, "message": "角色设置成功", "role": "BLIND" }
```

**Error:** `409` — Role already set, cannot be changed.

**Notes:** Setting role auto-creates empty `BlindProfile` or `VolunteerProfile` record.

---

## User Management

### Get User by ID

**Endpoint:** `GET /api/users/{id}`  
**Authentication:** Required (JWT, userId must match)

**Response:** `200 OK`
```json
{ "userId": 1, "phone": "138****8000", "role": "BLIND", "createdAt": "2026-04-10T10:30:00" }
```

### Delete Account (soft delete)

**Endpoint:** `DELETE /api/users/{id}`  
**Authentication:** Required (JWT, userId must match)

**Response:** `200 OK`
```json
{ "success": true, "message": "账号已注销" }
```

---

## Blind Profile

### Get Blind Profile

**Endpoint:** `GET /api/blind/profile`  
**Authentication:** Required (JWT, BLIND role)

**Response:** `200 OK`
```json
{
  "name": "张三",
  "runningPace": "6:00/km",
  "specialNeeds": "需要扶手辅助"
}
```

**Note:** Emergency contacts are managed separately via `/api/users/{userId}/emergency-contacts`.

### Update Blind Profile

**Endpoint:** `PUT /api/blind/profile`  
**Authentication:** Required (JWT, BLIND role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | No | User's name |
| runningPace | string | No | Running pace (e.g., "6:00/km") |
| specialNeeds | string | No | Special needs or requirements |

```json
{ "name": "张三", "runningPace": "6:00/km", "specialNeeds": "需要扶手辅助" }
```

**Response:** `200 OK`
```json
{ "success": true, "data": { "name": "张三", "runningPace": "6:00/km", "specialNeeds": "需要扶手辅助" } }
```

---

## Blind Location

### Report Blind User Location

Report real-time location for proximity detection with volunteers.

**Endpoint:** `POST /api/blind/location`  
**Authentication:** Required (JWT, BLIND role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| latitude | number | Yes | Latitude (-90 to 90) |
| longitude | number | Yes | Longitude (-180 to 180) |

```json
{ "latitude": 39.9042, "longitude": 116.4074 }
```

**Response:** `200 OK`
```json
{ "success": true, "message": "位置已更新" }
```

**Notes:** Stored in Redis as `blind:loc:{userId}` with 30s TTL. Used by ProximityService to detect when blind user is near volunteer.

---

## Emergency Contacts

### List Emergency Contacts

**Endpoint:** `GET /api/users/{userId}/emergency-contacts`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "data": [
  { "id": 1, "name": "李四", "phone": "139****1111", "relationship": "配偶", "isPrimary": true },
  { "id": 2, "name": "王五", "phone": "139****2222", "relationship": "父亲", "isPrimary": false }
]}
```

### Create Emergency Contact

**Endpoint:** `POST /api/users/{userId}/emergency-contacts`  
**Authentication:** Required (JWT)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | Yes | Contact name (max 50) |
| phone | string | Yes | Contact phone (max 20) |
| relationship | string | No | Relationship (max 20) |
| isPrimary | boolean | No | Set as primary contact (default: false) |

```json
{ "name": "李四", "phone": "13900001111", "relationship": "配偶", "isPrimary": true }
```

**Response:** `200 OK`
```json
{ "success": true, "data": { "id": 1, "name": "李四", "phone": "139****1111", "relationship": "配偶", "isPrimary": true } }
```

**Notes:** Maximum 5 contacts per user. Must have exactly 1 primary contact for order creation.

### Update Emergency Contact

**Endpoint:** `PUT /api/users/{userId}/emergency-contacts/{contactId}`  
**Authentication:** Required (JWT)

**Request Body:** Same fields as create.

### Delete Emergency Contact

**Endpoint:** `DELETE /api/users/{userId}/emergency-contacts/{contactId}`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true }
```

### Set Primary Contact

**Endpoint:** `PUT /api/users/{userId}/emergency-contacts/{contactId}/set-primary`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "message": "已设为主要联系人" }
```

---

## Volunteer Profile

### Get Volunteer Profile

**Endpoint:** `GET /api/volunteer/profile`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{
  "name": "王五",
  "verificationStatus": "APPROVED",
  "availableTimeSlots": [
    { "dayOfWeek": "MONDAY", "startTime": "06:00:00", "endTime": "08:00:00" }
  ]
}
```

### Update Volunteer Profile

**Endpoint:** `PUT /api/volunteer/profile`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| name | string | No | Volunteer's name |
| availableTimeSlots | array | No | Available time slots |

```json
{
  "name": "王五",
  "availableTimeSlots": [
    { "dayOfWeek": "MONDAY", "startTime": "06:00", "endTime": "08:00" }
  ]
}
```

---

## Volunteer Verification

### Submit Verification Document

**Endpoint:** `POST /api/volunteer/verification`  
**Authentication:** Required (JWT, VOLUNTEER role)  
**Content Type:** `multipart/form-data`

**Form Data:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| file | file | Yes | Max 10MB |

**Response:** `200 OK`
```json
{ "success": true, "status": "APPROVED" }
```

**Notes:** Current version auto-approves. Files stored to `app.upload.dir`.

### Get Verification Status

**Endpoint:** `GET /api/volunteer/verification/status`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "data": { "status": "APPROVED" } }
```

---

## Volunteer Location

### Update Volunteer Location

**Endpoint:** `POST /api/volunteer/location`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| latitude | number | Yes | -90 to 90 |
| longitude | number | Yes | -180 to 180 |
| isOnline | boolean | No | Default: true |

```json
{ "latitude": 39.9042, "longitude": 116.4074, "isOnline": true }
```

**Response:** `200 OK`
```json
{ "success": true, "message": "位置已更新" }
```

**Notes:** Dual-write to Redis (`vol:loc:{userId}`, TTL 30s) + MySQL. Must be within 10km of order start point to see available orders.

---

## Order Management

### Order Status Flow

```
PENDING_MATCH → PENDING_ACCEPT → IN_PROGRESS → DRIVER_EN_ROUTE → DRIVER_ARRIVED → COMPLETED
     ↓              ↓                ↓                ↓                ↓
     └──→ CANCELLED ←┘                │                │                │
                                      ↓                ↓                ↓
                               ┌── REMATCHING ←──────┘────────────────┘
                               │     (volunteer cancel)
                               └──→ CANCELLED (blind cancel)
```

**Status Descriptions:**
- `PENDING_MATCH` — Order created, waiting for volunteer matching
- `PENDING_ACCEPT` — Matched with volunteers, waiting for acceptance
- `IN_PROGRESS` — Volunteer accepted, service in progress
- `DRIVER_EN_ROUTE` — Volunteer is on the way to meeting point
- `DRIVER_ARRIVED` — Volunteer arrived at meeting point
- `COMPLETED` — Service completed
- `CANCELLED` — Order cancelled (tracked via `CancelledBy` enum)
- `REMATCHING` — Volunteer cancelled, system re-matching (blind can still cancel)
- `NO_VOLUNTEER` — No volunteer found after matching timeout

### Create Order

**Endpoint:** `POST /api/orders`  
**Authentication:** Required (JWT, BLIND role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| startLatitude | number | Yes | Starting point latitude |
| startLongitude | number | Yes | Starting point longitude |
| startAddress | string | Yes | Starting point address |
| plannedStartTime | string | Yes | ISO 8601 datetime, must be future |
| plannedEndTime | string | Yes | ISO 8601 datetime, after start |

```json
{
  "startLatitude": 39.9042,
  "startLongitude": 116.4074,
  "startAddress": "朝阳公园南门",
  "plannedStartTime": "2026-04-12T09:00:00",
  "plannedEndTime": "2026-04-12T10:00:00"
}
```

**Response:** `200 OK`
```json
{ "success": true, "data": { "id": 1001, "status": "PENDING_MATCH", ... } }
```

**Error:** `409` — Active order already exists. `400` — Must have at least 1 emergency contact.

### Accept Order

**Endpoint:** `POST /api/orders/{id}/accept`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "message": "已接单" }
```

**Notes:** Accepts both `PENDING_MATCH` and `PENDING_ACCEPT` status. Uses optimistic locking (`@Version`).

### Reject Order

**Endpoint:** `POST /api/orders/{id}/reject`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true }
```

### Volunteer En Route

**Endpoint:** `POST /api/orders/{id}/en-route`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "message": "志愿者已出发" }
```

**Notes:** Status changes to `DRIVER_EN_ROUTE`. Requires `IN_PROGRESS` status.

### Volunteer Arrived

**Endpoint:** `POST /api/orders/{id}/arrived`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "message": "志愿者已到达" }
```

**Notes:** Status changes to `DRIVER_ARRIVED`. Requires `DRIVER_EN_ROUTE` status.

### Finish Order

**Endpoint:** `POST /api/orders/{id}/finish`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "message": "订单已完成" }
```

**Notes:** Accepts `IN_PROGRESS`, `DRIVER_EN_ROUTE`, or `DRIVER_ARRIVED` status. Status changes to `COMPLETED`.

### Cancel Order

**Endpoint:** `POST /api/orders/{id}/cancel`
**Authentication:** Required (JWT)

**Request Body (optional):**
```json
{ "reason": "临时有事" }
```

**Response:** `200 OK`
```json
{ "success": true, "message": "订单已取消" }
```

**Cancel Rules:**
| Who | From Status | Result |
|-----|-------------|--------|
| Blind | PENDING_MATCH, PENDING_ACCEPT | → CANCELLED |
| Blind | REMATCHING | → CANCELLED (clears timeout fields) |
| Blind | IN_PROGRESS, DRIVER_EN_ROUTE, DRIVER_ARRIVED | → 403 (cannot cancel during service) |
| Volunteer | PENDING_ACCEPT, DRIVER_EN_ROUTE, DRIVER_ARRIVED | → REMATCHING (clears volunteer, re-triggers matching) |
| Volunteer | IN_PROGRESS | → CANCELLED (no-show) |

**Rematch Behavior:**
When a volunteer cancels and status becomes REMATCHING:
1. `volunteerId` is cleared, `rematchCount` incremented, `lastRematchAt` updated
2. `rematchNotifyAt` set to `now() + 300s` (DB-driven timeout)
3. `OrderCreatedEvent` published to re-trigger matching
4. Blind user notified: "志愿者已取消，正在重新匹配"
5. Every 10s, `TimeoutScheduler.checkRematchTimeout()` polls — if no volunteer accepted and `rematchNotifyAt` passed, sends reminder to blind and updates `rematchNotifyAt`
6. New volunteer accepts → clears `rematchNotifyAt`, notifies blind: "已为您匹配到新的志愿者"

### Keep Waiting (refresh match timeout)

**Endpoint:** `PUT /api/orders/{id}/keep-waiting`
**Authentication:** Required (JWT, BLIND role)

Blind user acknowledges the "no volunteer found" reminder and chooses to keep waiting. Resets `matchNotifyAt` to `now() + 300s`.

**Response:** `200 OK`
```json
{ "success": true }
```

**Error:** `400` — Order not in PENDING_MATCH status. `403` — Not your order.

### Get Available Orders

**Endpoint:** `GET /api/orders/available`  
**Authentication:** Required (JWT, VOLUNTEER role)

**Response:** `200 OK`
```json
{ "success": true, "data": [
  { "orderId": 1001, "startAddress": "朝阳公园南门", "distanceKm": 2.5, ... }
]}
```

**Notes:** Returns orders within 10km in `PENDING_MATCH`, `PENDING_ACCEPT`, or `REMATCHING` status.

### Get My Orders

**Endpoint:** `GET /api/orders/mine`  
**Authentication:** Required (JWT)

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| role | string | No | auto from token | `BLIND` or `VOLUNTEER` |
| status | string | No | all | Filter by status |
| page | integer | No | 0 | Page number |
| size | integer | No | 10 | Page size |

### Get Order Details

**Endpoint:** `GET /api/orders/{id}`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "data": { "id": 1001, "status": "IN_PROGRESS", ... } }
```

---

## Order Status Logs

### Get Status Logs

**Endpoint:** `GET /api/orders/{id}/status-logs`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "data": [
  { "fromStatus": null, "toStatus": "PENDING_MATCH", "createdAt": "..." },
  { "fromStatus": "PENDING_MATCH", "toStatus": "IN_PROGRESS", "createdAt": "..." },
  { "fromStatus": "IN_PROGRESS", "toStatus": "DRIVER_EN_ROUTE", "createdAt": "..." }
]}
```

**Notes:** Every status change is logged automatically via `OrderStatusLogService`.

---

## Emergency Events

### Trigger Emergency Event

**Endpoint:** `POST /api/emergency/trigger`  
**Authentication:** Required (JWT)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| orderId | long | Yes | Current order ID |
| gpsLat | decimal | No | Current latitude |
| gpsLng | decimal | No | Current longitude |

```json
{ "orderId": 1001, "gpsLat": 39.9042, "gpsLng": 116.4074 }
```

**Response:** `200 OK`
```json
{ "success": true, "eventId": 1, "status": "VOLUNTEER_NOTIFIED" }
```

**Flow:**
1. Cooldown check (60s via Redis `emergency:cooldown:{userId}`)
2. Validate order belongs to user
3. Create event → notify volunteer via WebSocket → start 30s timer
4. Volunteer responds or timeout → escalate if needed

**Error:** `403` — Not your order. `409` — Triggered too frequently.

### Volunteer Response

**Endpoint:** `PUT /api/emergency/{eventId}/volunteer-response?action=NEED_HELP`  
**Authentication:** Required (JWT, must be order's volunteer)

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| action | string | Yes | `NEED_HELP` or `FALSE_ALARM` |

**Response:** `200 OK`
```json
{ "success": true, "eventId": 1, "action": "NEED_HELP" }
```

**Notes:** Must respond within 30 seconds. `FALSE_ALARM` closes event immediately. `NEED_HELP` escalates to emergency contacts.

---

## CS Emergency Operations

All CS endpoints require a CS JWT token (carrying `csRole` claim). The `csUserId` is extracted from the JWT automatically — no need to pass it as a parameter.

### Get Pending Emergency Events

**Endpoint:** `GET /api/cs/emergency-events`
**Authentication:** Required (CS JWT — CS or ADMIN role)

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | Filter by status |

**Response:** `200 OK` — Array of `EmergencyEvent` objects.

### Accept Event

**Endpoint:** `PUT /api/cs/emergency-events/{eventId}/accept`
**Authentication:** Required (CS JWT — CS or ADMIN role)

### Notify Emergency Contact

**Endpoint:** `PUT /api/cs/emergency-events/{eventId}/notify-contact`
**Authentication:** Required (CS JWT — CS or ADMIN role)

**Notes:** Sends SMS to primary emergency contact. Console log in dev mode.

### Resolve Event

**Endpoint:** `PUT /api/cs/emergency-events/{eventId}/resolve?notes=已确认安全`
**Authentication:** Required (CS JWT — CS or ADMIN role)

### Mark False Alarm

**Endpoint:** `PUT /api/cs/emergency-events/{eventId}/false-alarm`
**Authentication:** Required (CS JWT — CS or ADMIN role)

---

## Call Records

### Initiate Call

**Endpoint:** `POST /api/orders/{orderId}/call/initiate`  
**Authentication:** Required (JWT)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| callerRole | string | No | `BLIND_USER` or `VOLUNTEER` |

```json
{ "callerRole": "BLIND_USER" }
```

**Response:** `200 OK`
```json
{ "success": true, "data": { "callRecordId": 1, "status": "CONNECTED", "virtualNumber": "17012345678", "message": "通话已接通（模拟）" } }
```

**Notes:** Mock implementation returns `CONNECTED` with a fake virtual number (170-prefix). Real Aliyun integration available when `aliyun.private-number.enabled=true`.

### Get Call Records

**Endpoint:** `GET /api/orders/{orderId}/call/records`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "data": [
  { "id": 1, "callerRole": "BLIND_USER", "status": "INITIATED", "createdAt": "..." }
]}
```

---

## Reviews

### Create Review

**Endpoint:** `POST /api/orders/{id}/review`  
**Authentication:** Required (JWT, BLIND role)

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| rating | integer | Yes | 1-5 stars |
| comment | string | No | Review comment |

```json
{ "rating": 5, "comment": "志愿者非常耐心，服务很棒！" }
```

**Notes:** Order must be `COMPLETED`. One review per order.

### Get Reviews

**Endpoint:** `GET /api/orders/{id}/reviews`  
**Authentication:** Required (JWT)

**Response:** `200 OK`
```json
{ "success": true, "data": [ { "rating": 5, "comment": "...", "reviewer": {...}, "createdAt": "..." } ] }
```

---

## Error Responses

### Response Formats

**Legacy Format** (auth):
```json
{ "error": "错误信息" }
```

**Standard Format** (orders, volunteers, etc.):
```json
{ "success": false, "code": 409, "message": "订单已被其他志愿者接单" }
```

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| 200 | Success |
| 400 | Bad Request — validation error |
| 401 | Unauthorized — missing/invalid JWT |
| 403 | Forbidden — insufficient permissions |
| 404 | Not Found — resource doesn't exist |
| 409 | Conflict — business rule violation |
| 413 | Payload Too Large — file upload exceeds 10MB |
| 500 | Internal Server Error |

### Exception Mapping

| Exception | HTTP Status | Use Case |
|-----------|-------------|----------|
| `AuthException` | 400 | Invalid verification code |
| `OrderNotFoundException` | 404 | Order doesn't exist |
| `OrderPermissionException` | 403 | Unauthorized order access |
| `DuplicateOrderException` | 409 | Active order already exists |
| `OrderStatusException` | 409 | Invalid state transition |
| `RoleAlreadySetException` | 409 | Role already set |
| `OptimisticLockingFailureException` | 409 | Concurrent order acceptance |

---

## WebSocket Protocol

### Connection

**Endpoint:** `ws://localhost:8081/ws/volunteer?token=<jwt_token>`

**Authentication:** JWT token in URL query parameter.

### Message Types

**NEW_ORDER** — New order matched with volunteer:
```json
{ "type": "NEW_ORDER", "orderId": 1001, "startAddress": "...", "distanceKm": 2.5, ... }
```

**ORDER_STATUS_CHANGED** — Order status updated:
```json
{ "type": "ORDER_STATUS_CHANGED", "orderId": 1001, "fromStatus": "IN_PROGRESS", "toStatus": "DRIVER_EN_ROUTE", "message": "志愿者已出发" }
```

**EMERGENCY_VOLUNTEER_ALERT** — Emergency event triggered (→ volunteer):
```json
{ "type": "EMERGENCY_VOLUNTEER_ALERT", "eventId": 1, "orderId": 1001, "message": "您陪伴的盲人用户触发了紧急求助，请在30秒内确认情况" }
```

**EMERGENCY_RESOLVED_BY_VOLUNTEER** — Volunteer resolved emergency (→ CS + blind):
```json
{ "type": "EMERGENCY_RESOLVED_BY_VOLUNTEER", "eventId": 1, "resolvedBy": "VOLUNTEER", "needHelp": false }
```

**EMERGENCY_ALERT** — Emergency event triggered (→ CS):
```json
{ "type": "EMERGENCY_ALERT", "eventId": 1, "userId": 1, "orderId": 1001, ... }
```

**EMERGENCY_CONTACT_NOTIFIED** — Contact notified (→ blind):
```json
{ "type": "EMERGENCY_CONTACT_NOTIFIED", "eventId": 1, "message": "已通过短信通知您的联系人李四" }
```

**PROXIMITY_ALERT** — Proximity detected (→ blind + volunteer):
```json
{ "type": "PROXIMITY_ALERT", "orderId": 1001, "distanceMeters": 50, "message": "您的志愿者已到达附近" }
```

**VOLUNTEER_LOCATION_UPDATE** — Real-time volunteer position (→ blind, during DRIVER_EN_ROUTE / DRIVER_ARRIVED):
```json
{ "type": "VOLUNTEER_LOCATION_UPDATE", "orderId": 1001, "lat": 39.9042, "lng": 116.4074, "timestamp": 1712841600000 }
```

**APP_NOTIFICATION** — General notification:
```json
{ "type": "APP_NOTIFICATION", "title": "...", "body": "..." }
```

### Session Management

Uses `UnifiedSessionRegistry` supporting blind, volunteer, and CS multi-role sessions. Previous `VolunteerSessionRegistry` is deprecated.

---

## Data Types and Enums

### UserRole
`UNSET` | `BLIND` | `VOLUNTEER`

### OrderStatus
`PENDING_MATCH` | `PENDING_ACCEPT` | `IN_PROGRESS` | `DRIVER_EN_ROUTE` | `DRIVER_ARRIVED` | `COMPLETED` | `CANCELLED` | `REMATCHING` | `NO_VOLUNTEER`

### CancelledBy
`BLIND` | `VOLUNTEER`

### VerificationStatus
`NONE` | `PENDING` | `APPROVED` | `REJECTED`

### EmergencyStatus
`PENDING` | `VOLUNTEER_NOTIFIED` | `VOLUNTEER_CONFIRMED` | `CS_HANDLING` | `CONTACT_NOTIFIED` | `RESOLVED` | `FALSE_ALARM`

### TriggerType
`BUTTON` | `AI_DETECTED` | `MANUAL`

### VolunteerAction
`FALSE_ALARM` | `NEED_HELP`

### CallRole
`BLIND_USER` | `VOLUNTEER`

### CallStatus
`INITIATED` | `CONNECTED` | `FAILED` | `NOT_AVAILABLE`

### NotifyType
`SMS_TO_CONTACT` | `SMS_TO_USER` | `APP_PUSH` | `AI_VOICE_CALL`

### NotifyStatus
`PENDING` | `SENT` | `FAILED`

### NotificationChannel
`APP_PUSH` | `SMS` | `WEBSOCKET`

### CSRole
`CS` | `ADMIN`

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8081 | Server port |
| `app.matching.max-distance-km` | 10 | Maximum matching distance (km) |
| `app.matching.max-candidates` | 3 | Maximum volunteers to notify |
| `app.websocket.endpoint` | /ws/volunteer | WebSocket endpoint |
| `app.volunteer.location-ttl-seconds` | 30 | Volunteer location TTL |
| `app.proximity.threshold-meters` | 100 | Proximity detection threshold |
| `app.emergency.cooldown-seconds` | 60 | Emergency trigger cooldown |
| `app.emergency.volunteer-timeout-seconds` | 30 | Volunteer response timeout |
| `app.rematch.timeout-seconds` | 300 | Rematch timeout before reminder |
| `app.match.timeout-seconds` | 300 | Match timeout before reminder |
| `aliyun.private-number.enabled` | false | Enable Aliyun privacy number |
| `sms.code.length` | 6 | Verification code length |
| `sms.code.ttl-minutes` | 5 | Verification code TTL |
| `sms.code.max-attempts` | 5 | Max verification attempts |
| `spring.servlet.multipart.max-file-size` | 10MB | Max file upload size |
| `app.upload.dir` | /tmp/blindrun-uploads/ | File upload directory |

---

## Phone Number Masking

All phone numbers in responses are masked: `138****8000` (first 3 + `****` + last 4).

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-04-10 | Initial release |
| 2.0.0 | 2026-04-11 | Emergency response, order status upgrade (DRIVER_EN_ROUTE/ARRIVED), emergency contacts CRUD, call records, proximity detection, blind location, CS operations, notification system, order status logs |
| 3.0.0 | 2026-04-11 | CS authentication (username/password login, JWT with csRole claim), rematch flow (REMATCHING status, volunteer cancel → re-match), CsController JWT auth, PrivateNumberService mock CONNECTED, real-time location forwarding to blind, proximity wired into location updates |
| 4.0.0 | 2026-04-12 | DB-driven polling replaces ScheduledExecutorService and Redis timeout keys; new TimeoutScheduler with 4 polling methods; match timeout (matchNotifyAt) + keep-waiting endpoint; overdue order detection (overdueNotified); OrderService constructor 8 params |

# 志愿者首页聚合接口 `GET /api/volunteer/dispatch-summary`

> 前端交接文档。本文件每个字段都与后端实际返回一一对应，无虚标字段。
> 契约源：`VolunteerDispatchSummaryResponse.java` + `VolunteerService.getDispatchSummary()`。

## 用途

志愿者首页一次性拿全首页所需数据，前端只发一次请求。覆盖场景：
- 接单开关（开/关）
- 不可接单原因（结构化，前端精确引导用户去哪一步）
- WebSocket 在线状态 + 最近位置（地图定位标记）
- 可接单范围圆（以自身位置为圆心画圆）
- 评分 + 接单/拒单/超时统计
- 当前活跃订单 + 近期 5 条服务记录

> ⚠️ 接单开关的**切换**用独立接口 `PUT /api/volunteer/dispatch-status`，本聚合接口只负责**读取**。

---

## 接口定义

| 项 | 值 |
|---|---|
| 方法 | `GET` |
| 路径 | `/api/volunteer/dispatch-summary` |
| 角色 | `VOLUNTEER`（SecurityConfig `/api/volunteer/**` 通配覆盖） |
| 鉴权 | `Authorization: Bearer <jwt>` |
| 请求参数 | 无（从 JWT 取 userId） |

### 成功响应 `200`

外层用 `ApiResponse` 包装：
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": { /* VolunteerDispatchSummaryResponse */ }
}
```

### 错误响应

| HTTP | 场景 | 说明 |
|---|---|---|
| 401 | 未带/无效 token | 全局 JSON 401 handler |
| 403 | 非 VOLUNTEER 角色 | `checkVolunteerRole` 抛 `PermissionDeniedException` |
| 404 | 志愿者资料不存在 | `ResourceNotFoundException` |

---

## 响应字段（`data` 对象）

### 1. 接单资格

| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `canDispatch` | boolean | 否 | 系统判定当前是否可被派单。`true` = 4 个前置条件全满足（开关开+已认证+注册完成+在线） |
| `notAvailableReasons` | `string[]` | 否（可空数组） | 不可接单的结构化原因码。`canDispatch=true` 时为 `[]`。枚举见下表 |
| `wantsDispatch` | boolean | 否 | 用户接单开关状态（**用户意愿**，区别于 `canDispatch` 的系统综合判定） |

**`notAvailableReasons` 枚举值**（`DispatchBlockReason`）：

| 值 | 触发条件 | 前端引导建议 |
|---|---|---|
| `REGISTRATION_INCOMPLETE` | `registrationStep != STEP_4_COMPLETED` | 引导去完成 4 步注册 |
| `NOT_VERIFIED` | 资质认证未通过（`verified=false`） | 引导上传资质证件等审核 |
| `DISPATCH_DISABLED` | 用户主动关了接单开关（`wantsDispatch=false`） | 提示打开接单开关（调 `PUT /dispatch-status`） |
| `OFFLINE` | 当前离线，Redis 无在线位置记录 | 提示打开定位/上线 |

> **前端处理优先级**（多条命中时按此顺序展示）：`REGISTRATION_INCOMPLETE` → `NOT_VERIFIED` → `DISPATCH_DISABLED` → `OFFLINE`

> ⚠️ "可服务时段不匹配"**不是**不可接单原因，不在此数组里，也不影响 `canDispatch`。多数志愿者未填时段，硬拦会导致所有人都接不了单。

### 2. 在线与位置

| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `isOnline` | boolean | 否 | WebSocket 是否在线（Redis 有 `isOnline=true` 记录，TTL 30s） |
| `lastLat` | number | 是 | 最近位置纬度（地图圆心）。**离线时为 null** |
| `lastLng` | number | 是 | 最近位置经度（地图圆心）。**离线时为 null** |
| `lastLocationAt` | string(datetime) | 是 | 最近位置更新时间。**离线时为 null**（Redis 30s TTL 过期，过期坐标不可信） |

> 离线时 `lastLat/lastLng/lastLocationAt` 全为 `null`，前端不画范围圆，提示"打开定位上线"。

### 3. 覆盖范围与可服务时段

| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `coverageRadiusKm` | int | 否 | 可接单覆盖圆半径（km）。**当前固定 10**（取全局 `app.matching.max-distance-km`），与"可接订单列表"同源 |
| `isWithinServiceTime` | boolean | 否 | 当前是否落在可服务时段内（后端按"今天周几+当前时间"算）。**仅 UI 提示，不阻断接单** |
| `availableTimeSlots` | `object[]` | 否（可空数组） | 可服务时间模板，前端渲染完整时段表。元素结构见下 |

**`availableTimeSlots` 元素**（`VolunteerAvailableTimeSlot`）：
```json
{ "dayOfWeek": "MONDAY", "startTime": "08:00:00", "endTime": "12:00:00" }
```
| 字段 | 类型 | 说明 |
|---|---|---|
| `dayOfWeek` | string | 星期，标准值 `MONDAY`~`SUNDAY` |
| `startTime` | string(time) | 起始时间 `HH:mm:ss` |
| `endTime` | string(time) | 结束时间 `HH:mm:ss` |

### 4. 评分与统计

| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `avgRating` | number | 是 | 平均评分 1.0-5.0。**null = 尚无评价** |
| `totalRatings` | int | 否 | 累计评价数（默认 0） |
| `totalDispatched` | int | 否 | 累计被派单次数 |
| `totalAccepted` | int | 否 | 累计**接单**次数（点了 ACCEPT 就算，含接了没跑完的） |
| `totalCompleted` | int | 否 | 累计**完成**订单次数（订单走到 COMPLETED 才算） |
| `totalDeclined` | int | 否 | 累计拒单次数（仅 DECLINE，**不含超时**） |
| `totalTimeout` | int | 否 | 累计响应超时次数（30s 未响应，**不计入接单率分母**） |
| `acceptanceRate` | number | 是 | 接单率 0.0-1.0。**null = 尚无派单记录** |

### 5. 订单列表

| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `activeOrders` | `object[]` | 否（可空数组） | 当前活跃订单（全量，通常 ≤1 条）。元素结构见下 |
| `recentOrders` | `object[]` | 否（可空数组） | 近期服务记录（最近 5 条，按创建时间倒序）。元素结构见下 |

**`activeOrders` 元素**（`VolunteerDispatchActiveOrder`）—— 对应 `IN_PROGRESS`/`DRIVER_EN_ROUTE`/`DRIVER_ARRIVED`：
| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `orderId` | int | 否 | 订单 ID |
| `status` | string | 否 | 订单状态枚举名 |
| `plannedStartTime` | string(datetime) | 否 | 计划开始时间 |
| `plannedEndTime` | string(datetime) | 否 | 计划结束时间 |
| `startAddress` | string | 否 | 起跑点文字地址 |
| `startLatitude` | number | 否 | 起跑点纬度 |
| `startLongitude` | number | 否 | 起跑点经度 |
| `blindName` | string | 否 | 盲人姓名（**已脱敏**，保留首字符，如 `张*`；2026-07-19 起） |
| `blindPhoneMasked` | string | 否 | 盲人手机号（**已脱敏**，如 `138****0001`） |
| `acceptedAt` | string(datetime) | 是 | 接单时间（接单后才有） |

**`recentOrders` 元素**（`VolunteerDispatchRecentOrder`）—— 最近 5 条任意状态订单：
| 字段 | 类型 | null | 说明 |
|---|---|---|---|
| `orderId` | int | 否 | 订单 ID |
| `status` | string | 否 | 订单状态枚举名 |
| `plannedStartTime` | string(datetime) | 否 | 计划开始时间 |
| `completedAt` | string(datetime) | 是 | 完成时间（仅 `COMPLETED` 有值，否则 null） |
| `rating` | int | 是 | 该订单收到的评分 1-5，**无评价为 null** |
| `startAddress` | string | 否 | 起跑点文字地址 |
| `blindName` | string | 否 | 盲人姓名（**已脱敏**，保留首字符，如 `张*`；2026-07-19 起） |

---

## 完整响应示例

```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "canDispatch": true,
    "notAvailableReasons": [],
    "wantsDispatch": true,
    "isOnline": true,
    "lastLat": 39.9042,
    "lastLng": 116.4074,
    "lastLocationAt": "2026-06-29T21:15:30",
    "coverageRadiusKm": 10,
    "isWithinServiceTime": true,
    "availableTimeSlots": [
      { "dayOfWeek": "MONDAY", "startTime": "08:00:00", "endTime": "12:00:00" },
      { "dayOfWeek": "WEDNESDAY", "startTime": "18:00:00", "endTime": "21:00:00" }
    ],
    "avgRating": 4.8,
    "totalRatings": 23,
    "totalDispatched": 30,
    "totalAccepted": 25,
    "totalDeclined": 3,
    "totalTimeout": 2,
    "acceptanceRate": 0.89,
    "activeOrders": [
      {
        "orderId": 1024,
        "status": "DRIVER_EN_ROUTE",
        "plannedStartTime": "2026-06-29T19:00:00",
        "plannedEndTime": "2026-06-29T20:00:00",
        "startAddress": "朝阳公园南门",
        "startLatitude": 39.9372,
        "startLongitude": 116.4736,
        "blindName": "张*",
        "blindPhoneMasked": "138****0001",
        "acceptedAt": "2026-06-29T18:55:00"
      }
    ],
    "recentOrders": [
      {
        "orderId": 1020,
        "status": "COMPLETED",
        "plannedStartTime": "2026-06-28T19:00:00",
        "completedAt": "2026-06-28T20:05:00",
        "rating": 5
      },
      {
        "orderId": 1018,
        "status": "COMPLETED",
        "plannedStartTime": "2026-06-27T07:00:00",
        "completedAt": "2026-06-27T08:00:00",
        "rating": null
      }
    ]
  }
}
```

### 离线示例（位置段为 null）

```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "canDispatch": false,
    "notAvailableReasons": ["OFFLINE"],
    "wantsDispatch": true,
    "isOnline": false,
    "lastLat": null,
    "lastLng": null,
    "lastLocationAt": null,
    "coverageRadiusKm": 10,
    "isWithinServiceTime": false,
    "availableTimeSlots": [],
    "avgRating": null,
    "totalRatings": 0,
    "totalDispatched": 0,
    "totalAccepted": 0,
    "totalDeclined": 0,
    "totalTimeout": 0,
    "acceptanceRate": null,
    "activeOrders": [],
    "recentOrders": []
  }
}
```

---

## 前端实现要点

### 1. 接单开关
- **读取**开关状态：用本接口的 `wantsDispatch`。
- **切换**开关：调 `PUT /api/volunteer/dispatch-status`，body `{"wantsDispatch": true/false}`，不依赖本接口。

### 2. 可接单范围圆（地图）
- 圆心：`(lastLat, lastLng)`；半径：`coverageRadiusKm * 1000`（米）。
- `lastLat == null`（离线）时不画圆，改为提示"打开定位上线"。
- 半径当前恒为 10km，但请用字段值而非硬编码，便于后端未来调整。

### 3. 不可接单提示
- `canDispatch === true` → 正常展示首页。
- 否则按 `notAvailableReasons` 的优先级展示引导（资质 > 认证 > 开关 > 上线）。

### 4. 脱敏
- `blindPhoneMasked` 已是脱敏值，前端直接展示，**不要**尝试还原。

---

## 后端实现位置

| 关注点 | 位置 |
|---|---|
| 端点 | `controller/VolunteerController.java` `getDispatchSummary()` |
| 聚合逻辑 | `service/VolunteerService.java` `getDispatchSummary(Long userId)` |
| 响应 DTO | `dto/volunteer/VolunteerDispatchSummaryResponse.java` |
| 活跃订单 DTO | `dto/volunteer/VolunteerDispatchActiveOrder.java` |
| 近期记录 DTO | `dto/volunteer/VolunteerDispatchRecentOrder.java` |
| 原因枚举 | `entity/DispatchBlockReason.java` |
| 覆盖半径配置 | `app.matching.max-distance-km`（`application.properties`，默认 10） |

## 数据来源对照（避免虚标）

| 字段 | 来源 |
|---|---|
| `wantsDispatch` / `verified` / `registrationStep` / 评分统计 | `VolunteerProfile` 实体（DB） |
| `isOnline` / `lastLat/Lng/At` | Redis `vol:loc:{userId}`（经 `VolunteerLocationService.getVolunteerLocation`） |
| `availableTimeSlots` | `volunteer_available_time` 表 |
| `activeOrders` | `RunOrderRepository.findByVolunteerIdAndStatusInFetchBlind` |
| `recentOrders` | `RunOrderRepository.findByVolunteerId`（Pageable，取 5 条） |
| `recentOrders[].rating` | `OrderReviewRepository.findByOrderIdIn`（批量，N+1 防护） |
| `coverageRadiusKm` | 全局配置 `app.matching.max-distance-km` |

> **本项目无积分系统**，本接口不返回积分字段。若产品后续需要，须先建积分系统再在此 DTO 增字段。

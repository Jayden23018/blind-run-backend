# 助盲跑 - Mermaid 图表

---

## 1. 实体关系图

```mermaid
erDiagram
    USER {
        long id PK
        string phone UK
        UserRole role
        LocalDateTime deleted_at
        LocalDateTime created_at
    }

    BLIND_PROFILE {
        long user_id PK FK
        string name
        string running_pace
        string special_needs
    }

    VOLUNTEER_PROFILE {
        long user_id PK FK
        string name
        boolean verified
        VerificationStatus verification_status
        string verification_doc_url
    }

    RUN_ORDER {
        long id PK
        long blind_user_id FK
        long volunteer_id FK
        double start_latitude
        double start_longitude
        string start_address
        OrderStatus status
        CancelledBy cancelled_by
        int rematch_count
        datetime match_notify_at
        datetime rematch_notify_at
        boolean overdue_notified
        Long version
    }

    VOLUNTEER_LOCATION {
        long id PK
        long volunteer_id FK
        double latitude
        double longitude
        boolean is_online
    }

    VOLUNTEER_AVAILABLE_TIME {
        long id PK
        long volunteer_id FK
        string day_of_week
        LocalTime start_time
        LocalTime end_time
    }

    ORDER_REVIEW {
        long id PK
        long order_id UK FK
        long reviewer_id FK
        long reviewee_id FK
        integer rating
        string comment
    }

    ORDER_STATUS_LOG {
        long id PK
        long order_id FK
        string from_status
        string to_status
        string remark
        LocalDateTime created_at
    }

    EMERGENCY_CONTACT {
        long id PK
        long user_id FK
        string name
        string phone
        string relationship
        boolean is_primary
    }

    EMERGENCY_EVENT {
        long id PK
        long order_id FK
        long user_id FK
        TriggerType trigger_type
        EmergencyStatus status
        BigDecimal gps_lat
        BigDecimal gps_lng
        VolunteerAction volunteer_action
        Long cs_user_id FK
    }

    EMERGENCY_NOTIFICATION {
        long id PK
        long event_id FK
        long contact_id FK
        NotifyType notify_type
        NotifyStatus status
        string content
    }

    CALL_RECORD {
        long id PK
        long order_id FK
        string caller_role
        string callee_role
        CallStatus status
    }

    CS_USER {
        long id PK
        string username UK
        string department
        CSRole role
    }

    NOTIFICATION_LOG {
        long id PK
        long order_id FK
        long user_id FK
        NotificationChannel channel
        NotifyStatus status
        string content
    }

    USER ||--o| BLIND_PROFILE : "1:1 @MapsId"
    USER ||--o| VOLUNTEER_PROFILE : "1:1 @MapsId"
    USER ||--o{ RUN_ORDER : "creates"
    USER ||--o{ RUN_ORDER : "accepts"
    USER ||--o{ VOLUNTEER_LOCATION : "reports"
    USER ||--o{ EMERGENCY_CONTACT : "has 1-5"
    RUN_ORDER ||--o| ORDER_REVIEW : "has"
    RUN_ORDER ||--o{ ORDER_STATUS_LOG : "logs"
    RUN_ORDER ||--o{ EMERGENCY_EVENT : "triggers"
    RUN_ORDER ||--o{ CALL_RECORD : "initiates"
    EMERGENCY_EVENT ||--o{ EMERGENCY_NOTIFICATION : "sends"
    EMERGENCY_CONTACT ||--o{ EMERGENCY_NOTIFICATION : "receives"
    CS_USER ||--o{ EMERGENCY_EVENT : "handles"
```

---

## 2. 订单状态机

```mermaid
stateDiagram-v2
    [*] --> PENDING_MATCH: 盲人创建订单

    PENDING_MATCH --> PENDING_ACCEPT: 系统匹配志愿者
    PENDING_MATCH --> IN_PROGRESS: 志愿者直接接单
    PENDING_MATCH --> CANCELLED: 盲人取消

    PENDING_ACCEPT --> IN_PROGRESS: 志愿者接单
    PENDING_ACCEPT --> CANCELLED: 盲人取消
    PENDING_ACCEPT --> REMATCHING: 志愿者取消

    IN_PROGRESS --> DRIVER_EN_ROUTE: 志愿者出发
    IN_PROGRESS --> COMPLETED: 完成/超时
    IN_PROGRESS --> CANCELLED: 志愿者取消（爽约）

    DRIVER_EN_ROUTE --> DRIVER_ARRIVED: 志愿者到达
    DRIVER_EN_ROUTE --> COMPLETED: 完成
    DRIVER_EN_ROUTE --> REMATCHING: 志愿者取消

    DRIVER_ARRIVED --> COMPLETED: 完成
    DRIVER_ARRIVED --> REMATCHING: 志愿者取消

    REMATCHING --> IN_PROGRESS: 新志愿者接单
    REMATCHING --> CANCELLED: 盲人取消

    COMPLETED --> [*]
    CANCELLED --> [*]

    note right of PENDING_MATCH
        订单已创建，
        正在匹配附近志愿者
    end note

    note right of REMATCHING
        志愿者已取消，
        系统正在重新匹配
    end note

    note right of DRIVER_EN_ROUTE
        志愿者正在前往
        会合点
    end note

    note right of DRIVER_ARRIVED
        志愿者已到达起点，
        准备开始服务
    end note
```

---

## 3. 紧急事件流程

```mermaid
sequenceDiagram
    actor Blind as 盲人用户
    participant ES as EmergencyService
    participant Redis as Redis
    participant NS as NotificationService
    participant WS as UnifiedSessionRegistry
    actor Vol as 志愿者
    actor CS as 客服用户
    participant Contact as 紧急联系人
    participant TS as TimeoutScheduler

    Blind->>ES: POST /api/emergency/trigger
    ES->>Redis: 检查冷却键（60秒）
    ES->>ES: 验证订单归属
    ES->>ES: 创建事件（PENDING）

    ES->>NS: 发送短信给盲人
    ES->>ES: 设置状态 VOLUNTEER_NOTIFIED + volunteer_timeout_at = now()+30s
    ES->>WS: 推送 EMERGENCY_VOLUNTEER_ALERT 给志愿者
    ES->>WS: 推送 EMERGENCY_ALERT 给客服
    ES-->>Blind: {eventId, status: VOLUNTEER_NOTIFIED}

    alt 志愿者需要帮助（30秒内）
        Vol->>ES: PUT volunteer-response?action=NEED_HELP
        ES->>ES: 设置状态 VOLUNTEER_CONFIRMED
        ES->>NS: 发送短信给紧急联系人
        ES->>WS: 推送 EMERGENCY_RESOLVED_BY_VOLUNTEER 给客服 + 盲人
    else 志愿者误触
        Vol->>ES: PUT volunteer-response?action=FALSE_ALARM
        ES->>ES: 设置状态 FALSE_ALARM
        ES->>WS: 推送已解决通知
    else 30秒超时 — 无响应（数据库轮询）
        TS->>TS: 每10秒轮询：status=VOLUNTEER_NOTIFIED AND volunteer_timeout_at < NOW()
        TS->>ES: handleVolunteerTimeout(eventId)
        ES->>ES: 检查状态仍为 VOLUNTEER_NOTIFIED
        ES->>ES: 升级 → 通知紧急联系人
    end

    CS->>ES: Accept → Notify Contact → Resolve / False Alarm
```

---

## 4. 认证流程

```mermaid
sequenceDiagram
    actor Client
    participant AuthCtrl as AuthController
    participant VCS as VerificationCodeService
    participant Redis
    participant UserRepo
    participant JwtUtil

    Client->>AuthCtrl: POST /api/auth/send-code {phone}
    AuthCtrl->>VCS: generateAndStoreCode(phone)
    VCS->>Redis: SET sms:code:{phone} {code,attempts} EX 300s
    VCS-->>AuthCtrl: code
    AuthCtrl-->>Client: {success: true}

    Client->>AuthCtrl: POST /api/auth/verify-code {phone, code}
    AuthCtrl->>VCS: verifyCode(phone, code)
    VCS->>Redis: GET sms:code:{phone}
    VCS->>Redis: 成功后删除
    VCS-->>AuthCtrl: true

    AuthCtrl->>UserRepo: findByPhone(phone)
    alt 新用户
        AuthCtrl->>UserRepo: save(newUser)
    end

    AuthCtrl->>JwtUtil: generateToken(userId)
    JwtUtil-->>AuthCtrl: JWT
    AuthCtrl-->>Client: {token, userId, role}
```

---

## 4b. 客服认证流程

```mermaid
sequenceDiagram
    actor CS as 客服控制台
    participant Ctrl as CsAuthController
    participant Svc as CSAuthService
    participant Repo as CSUserRepository
    participant Jwt as JwtUtil

    CS->>Ctrl: POST /api/cs/auth/login {username, password}
    Ctrl->>Svc: login(username, password)
    Svc->>Repo: findByUsername(username)

    alt 用户不存在
        Repo-->>Svc: empty
        Svc-->>Ctrl: RuntimeException("用户名或密码错误")
        Ctrl-->>CS: 401 {error: "..."}
    else 用户存在
        Repo-->>Svc: CSUser
        Svc->>Svc: BCrypt.matches(password, passwordHash)

        alt 密码错误
            Svc-->>Ctrl: RuntimeException("用户名或密码错误")
            Ctrl-->>CS: 401 {error: "..."}
        else 密码正确
            Svc->>Jwt: generateToken(userId, csRole)
            Jwt-->>Svc: JWT with csRole claim
            Svc-->>Ctrl: [token, role]
            Ctrl-->>CS: 200 {token: "...", role: "ADMIN"}
        end
    end
```

---

## 4c. 重新匹配流程（志愿者取消）

```mermaid
sequenceDiagram
    actor Vol as 志愿者
    participant OS as OrderService
    participant EP as EventPublisher
    participant NS as NotificationService
    actor Blind as 盲人用户
    participant TS as TimeoutScheduler
    actor NewVol as 新志愿者

    Vol->>OS: POST /api/orders/{id}/cancel
    OS->>OS: 检测到志愿者取消（PENDING_ACCEPT/EN_ROUTE/ARRIVED）
    OS->>OS: 设置状态 = REMATCHING，清除 volunteerId，rematchCount++
    OS->>OS: 设置 rematchNotifyAt = now() + 300s
    OS->>EP: publishEvent(OrderCreatedEvent)
    OS->>NS: sendOrderStatusChange → 盲人："志愿者已取消，正在重新匹配"
    OS-->>Vol: 200 OK

    alt 5分钟超时 — 无志愿者（数据库轮询）
        TS->>TS: 每10秒轮询：status=REMATCHING AND rematch_notify_at < NOW()
        TS->>OS: handleRematchTimeout(orderId)
        OS->>NS: sendOrderStatusChange → 盲人："暂时没有可用志愿者，仍在等待"
        OS->>OS: 更新 rematchNotifyAt = now() + 300s（循环）
    else 新志愿者接单
        NewVol->>OS: POST /api/orders/{id}/accept
        OS->>OS: 清除 rematchNotifyAt
        OS->>OS: 设置状态 = IN_PROGRESS
        OS->>NS: sendOrderStatusChange → 盲人："已为您匹配到新的志愿者"
    else 盲人在REMATCHING期间取消
        Blind->>OS: POST /api/orders/{id}/cancel
        OS->>OS: 清除 rematchNotifyAt
        OS->>OS: 设置状态 = CANCELLED
    end
```

---

## 5. 订单匹配流程

```mermaid
sequenceDiagram
    actor Blind as 盲人用户
    participant OrderSvc as OrderService
    participant EventPub as Event Publisher
    participant MatchSvc as MatchingService
    participant LocSvc as VolunteerLocationService
    participant Redis
    participant WS as UnifiedSessionRegistry

    Blind->>OrderSvc: POST /api/orders
    OrderSvc->>OrderSvc: 验证时间 + 无进行中订单 + 有紧急联系人
    OrderSvc->>OrderSvc: 保存订单（PENDING_MATCH）
    OrderSvc->>EventPub: publish(OrderCreatedEvent)
    EventPub-->>Blind: 201 Created

    Note over MatchSvc: 异步匹配开始
    MatchSvc->>LocSvc: getOnlineVolunteerLocations()
    LocSvc->>Redis: KEYS vol:loc:*

    loop 对每个志愿者
        MatchSvc->>MatchSvc: Haversine 距离计算到订单
    end

    MatchSvc->>MatchSvc: 按距离排序，限制3个

    loop 选中的候选人
        MatchSvc->>WS: sendToUser(volunteerId, NEW_ORDER)
    end

    MatchSvc->>OrderSvc: 更新状态为 PENDING_ACCEPT
```

---

## 6. 系统架构概览

```mermaid
flowchart TD
    subgraph "客户端层"
        A[盲人用户 App]
        B[志愿者 App]
        C[客服控制台]
    end

    subgraph "API 层 — 14个控制器"
        D[AuthController]
        E[RoleController]
        F[BlindController + BlindLocationController]
        G[EmergencyContactController]
        H[VolunteerController]
        I[OrderController + OrderStatusController]
        J[EmergencyController]
        K[CsController + CsAuthController]
        L[CallController]
        M[ReviewController]
        N[UserController]
    end

    subgraph "服务层 — 20个服务"
        O[AuthService + CSAuthService]
        P[OrderService]
        Q[MatchingService]
        R[EmergencyService]
        S[NotificationService]
        T[ProximityService]
        U[EmergencyContactService]
        V[OrderStatusLogService]
        W[BlindLocationService]
        X[VolunteerLocationService]
    end

    subgraph "数据层"
        Y[(MySQL — 15张表)]
        Z[(Redis — 5个键模式)]
    end

    A --> D & F & G & I & J & M & N
    B --> D & E & H & I & M
    C --> K

    D --> O
    I --> P & V
    P --> Q
    J --> R
    R --> S
    I --> T
    F --> W
    H --> X

    P & Q & R & S & T & U & V & W & X --> Y
    Q & T & W & X --> Z
```

---

## 7. WebSocket 生命周期

```mermaid
sequenceDiagram
    actor Client as 任意客户端（盲人/志愿者/客服）
    participant Handshake as JwtHandshakeInterceptor
    participant Handler as VolunteerWebSocketHandler
    participant Registry as UnifiedSessionRegistry

    Client->>Handshake: ws://host/ws/volunteer?token=jwt
    Handshake->>Handshake: 验证 JWT，提取 userId
    Handshake->>Handler: 带 userId 属性连接
    Handler->>Registry: register(userId, role, session)
    Handler-->>Client: 已连接

    Note over Registry: 多角色路由
    Registry->>Client: sendToUser(userId, json)
    Registry->>Client: sendToCs(json)

    Client->>Handler: WebSocket 关闭
    Handler->>Registry: unregister(userId)
```

---

## 8. 接近检测流程

```mermaid
flowchart TD
    A[志愿者上报位置] --> B[VolunteerLocationService.updateLocation]
    B --> C[写入 Redis vol:loc:userId + MySQL]
    C --> D[forwardLocationToBlind]
    D --> E{存在进行中的订单?}
    E -->|否| F[停止]
    E -->|是| G[推送 VOLUNTEER_LOCATION_UPDATE 给盲人]
    G --> H{订单状态 = DRIVER_EN_ROUTE?}
    H -->|否| F
    H -->|是| I[从 Redis 获取盲人位置 blind:loc:userId]
    I --> J{盲人位置可用?}
    J -->|否| F
    J -->|是| K[ProximityService.checkAndNotify]
    K --> L[Haversine 距离计算]
    L --> M{距离 < 阈值?}
    M -->|否| F
    M -->|是| N{已提醒过？Redis proximity:notified:orderId}
    N -->|是| F
    N -->|否| O[在 Redis 中设置 proximity:notified:orderId]
    O --> P[WebSocket 推送 PROXIMITY_ALERT 给盲人和志愿者]
```

---

## 关键设计模式

### 1. 事件驱动架构
- 订单创建 → `OrderCreatedEvent` → `@Async @EventListener` 在 MatchingService 中
- 解耦订单创建与匹配逻辑

### 2. 双写缓存
- 志愿者位置：Redis（TTL 30秒）+ MySQL
- 盲人位置：仅 Redis（TTL 30秒）
- 主存储：Redis 用于快速访问。备份：MySQL 用于持久化。

### 3. 乐观锁
- `RunOrder.@Version` 防止并发接单
- `OptimisticLockingFailureException` → 409 Conflict

### 4. 统一会话注册表
- 单个注册表用于盲人、志愿者和客服 WebSocket 会话
- 替代旧版按角色分离的会话注册表，统一为 `UnifiedSessionRegistry`

### 5. 数据库驱动轮询（TimeoutScheduler）
- 替代 `ScheduledExecutorService` 和 Redis 超时键
- 数据库字段（`volunteer_timeout_at`、`rematch_notify_at`、`match_notify_at`）作为超时标记
- 4个轮询方法：紧急超时（10秒）、重新匹配超时（10秒）、匹配超时（10秒）、超时订单（60秒）
- 崩溃安全：定时器在服务器重启后仍然有效（持久化在数据库中）

### 6. 去重/冷却键
- `emergency:cooldown:{userId}` — 触发速率限制（60秒）
- `proximity:notified:{orderId}` — 接近提醒去重

---

## 配置属性

```properties
# 匹配算法
app.matching.max-distance-km=10
app.matching.max-candidates=3

# WebSocket
app.websocket.endpoint=/ws/volunteer

# 位置 TTL
app.volunteer.location-ttl-seconds=30

# 接近检测
app.proximity.threshold-meters=100

# 紧急事件
app.emergency.cooldown-seconds=60
app.emergency.volunteer-timeout-seconds=30

# 重新匹配
app.rematch.timeout-seconds=300

# 匹配超时
app.match.timeout-seconds=300

# 隐私号
aliyun.private-number.enabled=false

# 文件上传
spring.servlet.multipart.max-file-size=10MB
app.upload.dir=/tmp/blindrun-uploads/
```

# Blind Running Companion (助盲跑) - Mermaid Diagrams

---

## 1. Entity Relationship Diagram

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

## 2. Order State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING_MATCH: Blind creates order

    PENDING_MATCH --> PENDING_ACCEPT: System matches volunteers
    PENDING_MATCH --> IN_PROGRESS: Volunteer accepts directly
    PENDING_MATCH --> CANCELLED: Blind cancels

    PENDING_ACCEPT --> IN_PROGRESS: Volunteer accepts
    PENDING_ACCEPT --> CANCELLED: Blind cancels
    PENDING_ACCEPT --> REMATCHING: Volunteer cancels

    IN_PROGRESS --> DRIVER_EN_ROUTE: Volunteer en-route
    IN_PROGRESS --> COMPLETED: Finish / Timeout
    IN_PROGRESS --> CANCELLED: Volunteer cancel (no-show)

    DRIVER_EN_ROUTE --> DRIVER_ARRIVED: Volunteer arrives
    DRIVER_EN_ROUTE --> COMPLETED: Finish
    DRIVER_EN_ROUTE --> REMATCHING: Volunteer cancels

    DRIVER_ARRIVED --> COMPLETED: Finish
    DRIVER_ARRIVED --> REMATCHING: Volunteer cancels

    REMATCHING --> IN_PROGRESS: New volunteer accepts
    REMATCHING --> CANCELLED: Blind cancels

    COMPLETED --> [*]
    CANCELLED --> [*]

    note right of PENDING_MATCH
        Order created, matching
        nearby volunteers
    end note

    note right of REMATCHING
        Volunteer cancelled,
        system re-matching
    end note

    note right of DRIVER_EN_ROUTE
        Volunteer heading to
        meeting point
    end note

    note right of DRIVER_ARRIVED
        Volunteer at start point,
        ready to begin
    end note
```

---

## 3. Emergency Event Flow

```mermaid
sequenceDiagram
    actor Blind as Blind User
    participant ES as EmergencyService
    participant Redis as Redis
    participant NS as NotificationService
    participant WS as UnifiedSessionRegistry
    actor Vol as Volunteer
    actor CS as CS User
    participant Contact as Emergency Contact
    participant TS as TimeoutScheduler

    Blind->>ES: POST /api/emergency/trigger
    ES->>Redis: Check cooldown key (60s)
    ES->>ES: Validate order ownership
    ES->>ES: Create event (PENDING)

    ES->>NS: Send SMS to blind
    ES->>ES: Set VOLUNTEER_NOTIFIED + volunteer_timeout_at = now()+30s
    ES->>WS: Push EMERGENCY_VOLUNTEER_ALERT to volunteer
    ES->>WS: Push EMERGENCY_ALERT to CS
    ES-->>Blind: {eventId, status: VOLUNTEER_NOTIFIED}

    alt Volunteer NEED_HELP (within 30s)
        Vol->>ES: PUT volunteer-response?action=NEED_HELP
        ES->>ES: Set VOLUNTEER_CONFIRMED
        ES->>NS: Send SMS to emergency contact
        ES->>WS: Push EMERGENCY_RESOLVED_BY_VOLUNTEER to CS + Blind
    else Volunteer FALSE_ALARM
        Vol->>ES: PUT volunteer-response?action=FALSE_ALARM
        ES->>ES: Set FALSE_ALARM
        ES->>WS: Push resolved notification
    else 30s timeout — no response (DB polling)
        TS->>TS: Poll every 10s: status=VOLUNTEER_NOTIFIED AND volunteer_timeout_at < NOW()
        TS->>ES: handleVolunteerTimeout(eventId)
        ES->>ES: Check status still VOLUNTEER_NOTIFIED
        ES->>ES: Escalate → notify emergency contact
    end

    CS->>ES: Accept → Notify Contact → Resolve / False Alarm
```

---

## 4. Authentication Flow

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
    VCS->>Redis: DELETE on success
    VCS-->>AuthCtrl: true

    AuthCtrl->>UserRepo: findByPhone(phone)
    alt New user
        AuthCtrl->>UserRepo: save(newUser)
    end

    AuthCtrl->>JwtUtil: generateToken(userId)
    JwtUtil-->>AuthCtrl: JWT
    AuthCtrl-->>Client: {token, userId, role}
```

---

## 4b. CS Authentication Flow

```mermaid
sequenceDiagram
    actor CS as CS Dashboard
    participant Ctrl as CsAuthController
    participant Svc as CSAuthService
    participant Repo as CSUserRepository
    participant Jwt as JwtUtil

    CS->>Ctrl: POST /api/cs/auth/login {username, password}
    Ctrl->>Svc: login(username, password)
    Svc->>Repo: findByUsername(username)

    alt User not found
        Repo-->>Svc: empty
        Svc-->>Ctrl: RuntimeException("用户名或密码错误")
        Ctrl-->>CS: 401 {error: "..."}
    else User found
        Repo-->>Svc: CSUser
        Svc->>Svc: BCrypt.matches(password, passwordHash)

        alt Password wrong
            Svc-->>Ctrl: RuntimeException("用户名或密码错误")
            Ctrl-->>CS: 401 {error: "..."}
        else Password correct
            Svc->>Jwt: generateToken(userId, csRole)
            Jwt-->>Svc: JWT with csRole claim
            Svc-->>Ctrl: [token, role]
            Ctrl-->>CS: 200 {token: "...", role: "ADMIN"}
        end
    end
```

---

## 4c. Rematch Flow (Volunteer Cancel)

```mermaid
sequenceDiagram
    actor Vol as Volunteer
    participant OS as OrderService
    participant EP as EventPublisher
    participant NS as NotificationService
    actor Blind as Blind User
    participant TS as TimeoutScheduler
    actor NewVol as New Volunteer

    Vol->>OS: POST /api/orders/{id}/cancel
    OS->>OS: Detect volunteer cancel (PENDING_ACCEPT/EN_ROUTE/ARRIVED)
    OS->>OS: Set status = REMATCHING, clear volunteerId, rematchCount++
    OS->>OS: Set rematchNotifyAt = now() + 300s
    OS->>EP: publishEvent(OrderCreatedEvent)
    OS->>NS: sendOrderStatusChange → blind: "志愿者已取消，正在重新匹配"
    OS-->>Vol: 200 OK

    alt 5 min timeout — no volunteer (DB polling)
        TS->>TS: Poll every 10s: status=REMATCHING AND rematch_notify_at < NOW()
        TS->>OS: handleRematchTimeout(orderId)
        OS->>NS: sendOrderStatusChange → blind: "暂时没有可用志愿者，仍在等待"
        OS->>OS: Update rematchNotifyAt = now() + 300s (cycle)
    else New volunteer accepts
        NewVol->>OS: POST /api/orders/{id}/accept
        OS->>OS: Clear rematchNotifyAt
        OS->>OS: Set status = IN_PROGRESS
        OS->>NS: sendOrderStatusChange → blind: "已为您匹配到新的志愿者"
    else Blind cancels during REMATCHING
        Blind->>OS: POST /api/orders/{id}/cancel
        OS->>OS: Clear rematchNotifyAt
        OS->>OS: Set status = CANCELLED
    end
```

---

## 5. Order Matching Flow

```mermaid
sequenceDiagram
    actor Blind as Blind User
    participant OrderSvc as OrderService
    participant EventPub as Event Publisher
    participant MatchSvc as MatchingService
    participant LocSvc as VolunteerLocationService
    participant Redis
    participant WS as UnifiedSessionRegistry

    Blind->>OrderSvc: POST /api/orders
    OrderSvc->>OrderSvc: Validate times + no active orders + has contacts
    OrderSvc->>OrderSvc: Save order (PENDING_MATCH)
    OrderSvc->>EventPub: publish(OrderCreatedEvent)
    EventPub-->>Blind: 200 Created

    Note over MatchSvc: Async matching starts
    MatchSvc->>LocSvc: getOnlineVolunteerLocations()
    LocSvc->>Redis: KEYS vol:loc:*

    loop For each volunteer
        MatchSvc->>MatchSvc: Haversine distance to order
    end

    MatchSvc->>MatchSvc: Sort by distance, limit 3

    loop Selected candidates
        MatchSvc->>WS: sendToUser(volunteerId, NEW_ORDER)
    end

    MatchSvc->>OrderSvc: Update status PENDING_ACCEPT
```

---

## 6. System Architecture Overview

```mermaid
flowchart TD
    subgraph "Client Layer"
        A[Blind User App]
        B[Volunteer App]
        C[CS Dashboard]
    end

    subgraph "API Layer — 14 Controllers"
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

    subgraph "Service Layer — 20 Services"
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

    subgraph "Data Layer"
        Y[(MySQL — 15 tables)]
        Z[(Redis — 5 key patterns)]
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

## 7. WebSocket Lifecycle

```mermaid
sequenceDiagram
    actor Client as Any Client (Blind/Volunteer/CS)
    participant Handshake as JwtHandshakeInterceptor
    participant Handler as VolunteerWebSocketHandler
    participant Registry as UnifiedSessionRegistry

    Client->>Handshake: ws://host/ws/volunteer?token=jwt
    Handshake->>Handshake: Validate JWT, extract userId
    Handshake->>Handler: Connect with userId attribute
    Handler->>Registry: register(userId, role, session)
    Handler-->>Client: Connected

    Note over Registry: Multi-role routing
    Registry->>Client: sendToUser(userId, json)
    Registry->>Client: sendToCs(json)

    Client->>Handler: WebSocket close
    Handler->>Registry: unregister(userId)
```

---

## 8. Proximity Detection Flow

```mermaid
flowchart TD
    A[Volunteer reports location] --> B[VolunteerLocationService.updateLocation]
    B --> C[Write Redis vol:loc:userId + MySQL]
    C --> D[forwardLocationToBlind]
    D --> E{Active order exists?}
    E -->|No| F[Stop]
    E -->|Yes| G[Push VOLUNTEER_LOCATION_UPDATE to blind]
    G --> H{Order status = DRIVER_EN_ROUTE?}
    H -->|No| F
    H -->|Yes| I[Get blind location from Redis blind:loc:userId]
    I --> J{Blind location available?}
    J -->|No| F
    J -->|Yes| K[ProximityService.checkAndNotify]
    K --> L[Haversine distance calc]
    L --> M{Distance < threshold?}
    M -->|No| F
    M -->|Yes| N{Already notified? Redis proximity:notified:orderId}
    N -->|Yes| F
    N -->|No| O[Set proximity:notified:orderId in Redis]
    O --> P[WebSocket PROXIMITY_ALERT to blind + volunteer]
```

---

## Key Design Patterns

### 1. Event-Driven Architecture
- Order creation → `OrderCreatedEvent` → `@Async @EventListener` in MatchingService
- Decouples order creation from matching logic

### 2. Dual-Write Caching
- Volunteer locations: Redis (TTL 30s) + MySQL
- Blind locations: Redis (TTL 30s) only
- Primary: Redis for speed. Fallback: MySQL for persistence.

### 3. Optimistic Locking
- `RunOrder.@Version` prevents concurrent accept
- `OptimisticLockingFailureException` → 409 Conflict

### 4. Unified Session Registry
- Single registry for blind, volunteer, and CS WebSocket sessions
- Replaces per-role `VolunteerSessionRegistry`

### 5. DB-Driven Polling (TimeoutScheduler)
- Replaces `ScheduledExecutorService` and Redis timeout keys
- DB fields (`volunteer_timeout_at`, `rematch_notify_at`, `match_notify_at`) as timeout markers
- 4 polling methods: emergency timeout (10s), rematch timeout (10s), match timeout (10s), overdue orders (60s)
- Crash-safe: timers survive server restart (persisted in DB)

### 6. Dedup / Cooldown Keys
- `emergency:cooldown:{userId}` — trigger rate limit (60s)
- `proximity:notified:{orderId}` — proximity alert dedup

---

## Configuration Properties

```properties
# Matching
app.matching.max-distance-km=10
app.matching.max-candidates=3

# WebSocket
app.websocket.endpoint=/ws/volunteer

# Location TTL
app.volunteer.location-ttl-seconds=30

# Proximity
app.proximity.threshold-meters=100

# Emergency
app.emergency.cooldown-seconds=60
app.emergency.volunteer-timeout-seconds=30

# Rematch
app.rematch.timeout-seconds=300

# Match timeout
app.match.timeout-seconds=300

# Privacy Number
aliyun.private-number.enabled=false

# File Upload
spring.servlet.multipart.max-file-size=10MB
app.upload.dir=/tmp/blindrun-uploads/
```

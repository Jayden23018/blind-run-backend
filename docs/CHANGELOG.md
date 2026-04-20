# 变更日志

## [1.0.0] - 2026-04-14

### 新增

#### 核心功能
- 实现完整的订单生命周期管理
  - 订单创建、匹配、接单、取消、完成
  - 7种订单状态流转
  - 乐观锁防止并发接单
- 实现志愿者注册流程（4步）
  - STEP_1: 基本信息
  - STEP_2: 身份证上传（管理员审核）
  - STEP_3: 人脸验证（当前为Stub实现）
  - STEP_4: 培训课程和测验
- 实现紧急求助系统
  - 盲人触发紧急求助
  - 志愿者响应（30秒超时）
  - 客服介入处理
  - 紧急联系人通知
- 实现实时匹配系统
  - 基于地理位置的志愿者匹配
  - Redis位置缓存（30秒TTL）
  - 最多推送3名志愿者
- 实现WebSocket实时通信
  - 订单推送
  - 通知推送
  - 紧急求助推送
  - 多角色会话管理

#### 认证授权
- JWT无状态认证
- 短信验证码登录（6位，5分钟有效期）
- 客服用户名密码登录（BCrypt加密）
- 角色权限管理（BLIND/VOLUNTEER/CS/ADMIN）
- 软删除用户（deletedAt字段）

#### 档案管理
- 盲人用户档案（姓名、配速、特殊需求）
- 志愿者档案（姓名、电话、认证状态）
- 紧急联系人管理（1-5个，支持主联系人）

#### 位置服务
- 志愿者位置上报（Redis + MySQL双写）
- 盲人位置上报
- 接近检测（100米阈值）
- 位置脱敏（手机号隐藏）

#### 通知系统
- 数据库驱动的通知模板
- WebSocket实时推送
- 短信通知（当前为模拟实现）
- 支持TTS文本和优先级

#### 评价系统
- 订单完成评价（1-5星）
- 双向评价（盲人→志愿者，志愿者→盲人）
- 评论文本记录

#### 隐私号通话
- 阿里云隐私号集成（可配置）
- Mock实现（返回虚拟号码）
- 通话记录存储

#### 管理功能
- 管理员审核志愿者身份证
- 培训课程CRUD
- 通知模板管理
- 客服处理紧急事件

#### 定时任务
- 订单超时自动完成（60秒）
- 紧急求助超时检测（10秒轮询）
- 重新匹配超时提醒（10秒轮询）
- 匹配超时提醒（10秒轮询）
- 订单超时提醒（60秒轮询）

#### 限流保护
- Redis限流（3个桶）
  - auth: 10次/分钟
  - registration: 20次/分钟
  - general: 60次/分钟
- HTTP 429响应
- Retry-After头

#### 测试
- 116个测试用例
  - 113个常规测试
  - 3个慢速测试（OrderTimeoutTest×2, ConcurrencyTest）
- Testcontainers集成测试
- 软删除一致性测试
- 限流集成测试

#### 文档
- Swagger UI集成
- OpenAPI 3.0规范
- 完整的中文代码注释

### 技术栈

#### 后端框架
- Spring Boot 3.4.4
- Spring Security 6.x
- Spring Data JPA
- Spring WebSocket

#### 数据库
- MySQL 8.x（主数据库）
- Redis 6.x（缓存、限流、位置）
- H2（测试数据库）

#### 认证
- JWT (io.jsonwebtoken:jjwt-api:0.12.5)
- BCrypt密码加密

#### 文档
- SpringDoc OpenAPI 2.8.6

#### 测试
- JUnit 5
- Testcontainers 2.0.4
- Jedis（测试Redis客户端）

#### 工具
- Lombok
- Gradle 8.x
- Git

### 配置项

#### 匹配算法
- `app.matching.max-distance-km`: 最大匹配距离（默认10km）
- `app.matching.max-candidates`: 最多推送志愿者数（默认3）

#### WebSocket
- `app.websocket.endpoint`: WebSocket端点（默认/ws/volunteer）

#### 位置服务
- `app.volunteer.location-ttl-seconds`: 志愿者位置TTL（默认30秒）
- `app.proximity.threshold-meters`: 接近阈值（默认100米）

#### 紧急事件
- `app.emergency.cooldown-seconds`: 冷却时间（默认60秒）
- `app.emergency.volunteer-timeout-seconds`: 志愿者超时（默认30秒）

#### 限流
- `rate-limit.enabled`: 是否启用限流（默认true）
- `rate-limit.auth.max-requests`: 认证限流（默认10/分钟）
- `rate-limit.registration.max-requests`: 注册限流（默认20/分钟）
- `rate-limit.general.max-requests`: 通用限流（默认60/分钟）

### 数据库表

#### 用户相关（7张）
- users（用户表）
- blind_profiles（盲人档案）
- volunteer_profiles（志愿者档案）
- volunteer_locations（志愿者位置）
- volunteer_available_times（志愿者可用时间）
- emergency_contacts（紧急联系人）
- cs_users（客服用户）

#### 订单相关（7张）
- run_orders（订单表）
- order_status_logs（订单状态日志）
- order_reviews（订单评价）
- emergency_events（紧急事件）
- emergency_notifications（紧急通知记录）
- call_records（通话记录）
- notification_logs（通知日志）

#### 系统相关（5张）
- notification_templates（通知模板）
- training_courses（培训课程）
- training_progress（培训进度）
- training_quiz_questions（测验题目）
- training_quiz_attempts（测验记录）

### API端点

#### 认证（3个）
- POST /api/auth/send-code
- POST /api/auth/verify-code
- GET /api/auth/me

#### 角色管理（1个）
- POST /api/user/role

#### 用户管理（2个）
- GET /api/users/{id}
- DELETE /api/users/{id}

#### 盲人用户（2个）
- GET /api/blind/profile
- PUT /api/blind/profile

#### 志愿者（5个）
- GET /api/volunteer/profile
- PUT /api/volunteer/profile
- POST /api/volunteer/verification
- GET /api/volunteer/verification/status
- POST /api/volunteer/location

#### 志愿者注册（8个）
- GET /api/volunteer/registration/status
- POST /api/volunteer/registration/step1
- POST /api/volunteer/registration/step2/id-card
- POST /api/volunteer/registration/step3/face-verify/init
- GET /api/volunteer/registration/training/courses
- POST /api/volunteer/registration/training/progress
- GET /api/volunteer/registration/training/quiz/{courseId}
- POST /api/volunteer/registration/training/quiz/answer

#### 订单（11个）
- POST /api/orders
- POST /api/orders/{id}/accept
- POST /api/orders/{id}/reject
- POST /api/orders/{id}/finish
- POST /api/orders/{id}/cancel
- PUT /api/orders/{id}/keep-waiting
- GET /api/orders/available
- GET /api/orders/{id}
- GET /api/orders/mine
- GET /api/orders/{id}/status-logs
- POST /api/orders/{id}/en-route
- POST /api/orders/{id}/arrived

#### 评价（2个）
- POST /api/orders/{id}/review
- GET /api/orders/{id}/reviews

#### 紧急求助（2个）
- POST /api/emergency/trigger
- PUT /api/emergency/{eventId}/volunteer-response

#### 紧急联系人（5个）
- GET /api/users/{userId}/emergency-contacts
- POST /api/users/{userId}/emergency-contacts
- PUT /api/users/{userId}/emergency-contacts/{contactId}
- DELETE /api/users/{userId}/emergency-contacts/{contactId}
- PUT /api/users/{userId}/emergency-contacts/{contactId}/set-primary

#### 客服（5个）
- POST /api/cs/auth/login
- GET /api/cs/emergency-events
- PUT /api/cs/emergency-events/{id}/accept
- PUT /api/cs/emergency-events/{id}/notify-contact
- PUT /api/cs/emergency-events/{id}/resolve
- PUT /api/cs/emergency-events/{id}/false-alarm

#### 管理员（6个）
- GET /api/admin/notification-templates
- PUT /api/admin/notification-templates/{id}
- GET /api/admin/volunteers/review/id
- POST /api/admin/volunteers/review/id
- GET /api/admin/volunteers/training/stats
- POST /api/admin/volunteers/training/courses
- PUT /api/admin/volunteers/training/courses/{id}
- DELETE /api/admin/volunteers/training/courses/{id}

#### 位置（1个）
- POST /api/blind/location

#### 通话（2个）
- POST /api/orders/{orderId}/call/initiate
- GET /api/orders/{orderId}/call/records

#### WebSocket
- WS /ws/volunteer?token=<jwt>

### 已知问题

#### 待实现功能
- [ ] 真实短信服务集成（当前为Mock实现）
- [ ] 人脸识别系统集成（当前为Stub实现）
- [ ] 阿里云隐私号配置（当前为Mock实现）
- [ ] 文件存储服务（当前使用本地存储）
- [ ] 分布式Session（当前使用JWT无状态）

#### 技术债务
- [ ] WebSocket消息队列（防止消息丢失）
- [ ] 分布式限流（当前为单机限流）
- [ ] 数据库读写分离
- [ ] 缓存预热机制
- [ ] 监控和告警系统

### 贡献者

- Jayden23018

---

## 变更说明格式

- **新增**: 新增的功能
- **变更**: 功能的变更
- **废弃**: 即将移除的功能
- **移除**: 已移除的功能
- **修复**: Bug修复
- **安全**: 安全相关的修复

---

## 版本命名规则

遵循语义化版本（Semantic Versioning）:
- MAJOR.MINOR.PATCH
- MAJOR: 不兼容的API变更
- MINOR: 向下兼容的功能新增
- PATCH: 向下兼容的Bug修复

# 前后端联调指南

## 概述

本文档提供前后端联调的完整指南，包括后端启动、API测试、WebSocket连接等内容。

---

## 一、后端启动

### 1.1 环境准备

**必需服务**:
- ✅ MySQL 8.x（运行中，已创建 `spring_demo` 数据库）
- ✅ Redis 6.x（运行中，默认端口6379）
- ✅ JDK 17+

**检查服务状态**:
```bash
# 检查MySQL
mysql -u root -p -e "SHOW DATABASES LIKE 'spring_demo';"

# 检查Redis
redis-cli ping
# 应返回 PONG
```

### 1.2 启动后端服务

**方式一：使用Gradle（推荐开发调试）**
```bash
# 进入项目目录
cd /path/to/blind-run-backend

# 启动服务
./gradlew bootRun
```

**方式二：使用IDE**
- IntelliJ IDEA: 打开 `DemoApplication.java` → 右键 → Run 'DemoApplication'
- VS Code: 安装 Spring Boot Extension，点击启动按钮

**成功标志**:
```
Tomcat started on port 8081 (http). 
Started DemoApplication in X.XXX seconds.
```

### 1.3 验证服务启动

```bash
# 访问Swagger UI
open http://localhost:8081/swagger-ui/index.html
```

---

## 二、API测试

### 2.1 访问API文档

**Swagger UI**（推荐）:
```
http://localhost:8081/swagger-ui/index.html
```
- 📖 在线API文档，支持直接测试
- 🔍 可以搜索接口
- 🧪 支持填写参数并发送请求

**OpenAPI JSON**（导入Postman）:
```
http://localhost:8081/v3/api-docs
```

### 2.2 获取测试账号

#### 盲人用户

**注册并登录**:
```bash
# 1. 发送验证码
curl -X POST http://localhost:8081/api/auth/send-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "13900000001"}'

# 2. 查看验证码（控制台会输出）
# 【模拟短信】验证码: 123456

# 3. 验证码登录
curl -X POST http://localhost:8081/api/auth/verify-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "13900000001", "code": "123456"}'

# 响应示例:
# {
#   "token": "eyJhbGciOiJIUzI1NiJ9...",
#   "userId": 1,
#   "role": "UNSET"
# }

# 4. 设置为盲人角色
curl -X POST http://localhost:8081/api/user/role \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -d '{"role": "BLIND"}'
```

#### 志愿者用户

**注册并登录**:
```bash
# 1. 发送验证码（使用不同手机号）
curl -X POST http://localhost:8081/api/auth/send-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "13900000002"}'

# 2. 查看验证码（控制台输出）
# 【模拟短信】验证码: 654321

# 3. 验证码登录
curl -X POST http://localhost:8081/api/auth/verify-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "13900000002", "code": "654321"}'

# 4. 设置为志愿者角色
curl -X POST http://localhost:8081/api/user/role \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -d '{"role": "VOLUNTEER"}'
```

#### 客服用户

**登录**:
```bash
# 客服使用用户名密码登录（不是验证码）
curl -X POST http://localhost:8081/api/cs/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "cs001", "password": "password123"}'
```

### 2.3 常用API测试

#### 获取当前用户信息
```bash
curl -X GET http://localhost:8081/api/auth/me \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### 创建订单（盲人用户）
```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Authorization: Bearer BLIND_USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "startLatitude": 39.9042,
    "startLongitude": 116.4074,
    "startAddress": "朝阳公园南门",
    "plannedStartTime": "2026-04-16T14:00:00",
    "plannedEndTime": "2026-04-16T15:00:00"
  }'
```

#### 查看可用订单（志愿者）
```bash
curl -X GET http://localhost:8081/api/orders/available \
  -H "Authorization: Bearer VOLUNTEER_TOKEN"
```

#### 接单（志愿者）
```bash
curl -X POST http://localhost:8081/api/orders/1/accept \
  -H "Authorization: Bearer VOLUNTEER_TOKEN"
```

---

## 三、WebSocket连接

### 3.1 连接地址

```
ws://localhost:8081/ws/volunteer?token=YOUR_JWT_TOKEN
```

### 3.2 连接示例

**JavaScript (前端)**:
```javascript
const token = localStorage.getItem('jwtToken'); // 从JWT获取
const ws = new WebSocket(`ws://localhost:8081/ws/volunteer?token=${token}`);

ws.onopen = () => {
  console.log('✅ WebSocket connected');
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  console.log('📨 Received:', message);
  
  // 处理不同类型的消息
  switch(message.type) {
    case 'NEW_ORDER':
      console.log('🆕 新订单:', message.data);
      break;
    case 'NOTIFICATION':
      console.log('🔔 通知:', message.data);
      break;
    case 'EMERGENCY':
      console.log('🚨 紧急求助:', message.data);
      break;
  }
};

ws.onerror = (error) => {
  console.error('❌ WebSocket error:', error);
};

ws.onclose = () => {
  console.log('🔌 WebSocket disconnected');
};
```

### 3.3 消息类型

| 消息类型 | 触发时机 | 目标用户 |
|---------|---------|---------|
| `NEW_ORDER` | 新订单创建 | 志愿者 |
| `NOTIFICATION` | 订单状态变更 | 盲人、志愿者 |
| `EMERGENCY` | 紧急求助触发 | 志愿者、客服 |
| `SYSTEM` | 系统公告 | 所有用户 |

### 3.4 心跳保活

```javascript
// 每30秒发送一次心跳
setInterval(() => {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'PING' }));
  }
}, 30000);
```

---

## 四、前端配置

### 4.1 环境变量

创建 `.env.development` 文件：
```env
VITE_API_BASE_URL=http://localhost:8081
VITE_WS_URL=ws://localhost:8081/ws/volunteer
```

### 4.2 Axios配置示例

```javascript
// api/axiosConfig.js
import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 10000,
});

// 请求拦截器：添加JWT token
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('jwtToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截器：处理401未认证
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // 清除token，跳转登录
      localStorage.removeItem('jwtToken');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
```

### 4.3 WebSocket配置示例

```javascript
// utils/websocket.js
class WebSocketManager {
  constructor() {
    this.ws = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectDelay = 5000;
  }

  connect(token) {
    const wsUrl = `${import.meta.env.VITE_WS_URL}?token=${token}`;
    this.ws = new WebSocket(wsUrl);

    this.ws.onopen = () => {
      console.log('✅ WebSocket connected');
      this.reconnectAttempts = 0;
    };

    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      this.handleMessage(message);
    };

    this.ws.onerror = (error) => {
      console.error('❌ WebSocket error:', error);
    };

    this.ws.onclose = () => {
      console.log('🔌 WebSocket disconnected');
      this.reconnect(token);
    };
  }

  reconnect(token) {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1);
      
      console.log(`🔄 重连中... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`);
      
      setTimeout(() => {
        this.connect(token);
      }, delay);
    } else {
      console.error('❌ 重连失败，请刷新页面');
    }
  }

  handleMessage(message) {
    // 根据消息类型分发到不同的处理函数
    const handlers = {
      'NEW_ORDER': this.handleNewOrder,
      'NOTIFICATION': this.handleNotification,
      'EMERGENCY': this.handleEmergency,
      'SYSTEM': this.handleSystem,
    };

    const handler = handlers[message.type];
    if (handler) {
      handler(message.data);
    }
  }

  handleNewOrder(data) {
    console.log('🆕 新订单:', data);
    // 触发前端通知、更新UI等
  }

  handleNotification(data) {
    console.log('🔔 通知:', data);
    // 显示通知、更新订单状态等
  }

  handleEmergency(data) {
    console.log('🚨 紧急求助:', data);
    // 显示紧急提醒、播放声音等
  }

  handleSystem(data) {
    console.log('📢 系统消息:', data);
    // 显示系统公告
  }
}

export default new WebSocketManager();
```

---

## 五、常见问题排查

### 5.1 后端无法启动

**问题**: 启动失败，提示连接数据库或Redis失败

**解决方案**:
```bash
# 检查MySQL是否运行
mysql -u root -p -e "SELECT 1;"
# 或
systemctl status mysql

# 检查Redis是否运行
redis-cli ping
# 或
systemctl status redis

# 检查端口占用
lsof -i :8081
```

### 5.2 CORS跨域问题

**问题**: 前端请求被CORS阻止

**解决方案**: 后端已配置CORS，检查前端URL是否正确：
- 开发环境: `http://localhost:3000` 或 `http://127.0.0.1:3000`
- 避免使用 `http://localhost`（macOS可能有问题）

### 5.3 WebSocket连接失败

**问题**: WebSocket连接不上

**排查步骤**:
```bash
# 1. 检查token是否有效
curl -X GET http://localhost:8081/api/auth/me \
  -H "Authorization: Bearer YOUR_TOKEN"

# 2. 检查WebSocket配置
# 查看 SecurityConfig 是否包含: /ws/volunteer/**

# 3. 查看后端日志
tail -f logs/spring.log | grep -i websocket
```

### 5.4 401未认证错误

**问题**: 所有请求都返回401

**解决方案**:
```bash
# 1. 确认token格式
Authorization: Bearer YOUR_TOKEN_HERE
# 注意Bearer后面有个空格

# 2. 检查token是否过期
# JWT默认24小时过期，需要重新登录

# 3. 测试token是否有效
curl -X GET http://localhost:8081/api/auth/me \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 5.5 429限流错误

**问题**: 触发限流，返回429状态码

**原因**: 
- 认证接口: 10次/分钟
- 注册接口: 20次/分钟
- 通用接口: 60次/分钟

**临时解决方案**:
```bash
# 等待1分钟后重试，或修改配置
# src/test/resources/application.properties:
rate-limit.enabled=false
```

### 5.6 订单创建失败

**问题**: 创建订单时提示错误

**常见原因**:
1. 未设置紧急联系人
2. 已有进行中的订单
3. 时间参数不合理

**解决方案**:
```bash
# 1. 检查是否设置紧急联系人
curl -X GET http://localhost:8081/api/users/1/emergency-contacts \
  -H "Authorization: Bearer BLIND_TOKEN"

# 2. 创建紧急联系人
curl -X POST http://localhost:8081/api/users/1/emergency-contacts \
  -H "Authorization: Bearer BLIND_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "紧急联系人1",
    "phone": "13800138000",
    "relationship": "配偶",
    "isPrimary": true
  }'

# 3. 检查进行中的订单
curl -X GET http://localhost:8081/api/orders/mine \
  -H "Authorization: Bearer BLIND_TOKEN"
```

---

## 六、调试技巧

### 6.1 查看后端日志

```bash
# 实时查看日志
tail -f logs/spring.log

# 查看特定内容
tail -f logs/spring.log | grep -i "error"
tail -f logs/spring.log | grep -i "websocket"
```

### 6.2 使用Postman测试

**导入OpenAPI规范**:
1. 打开Postman
2. 点击 Import → 选择 "Link"
3. 输入: `http://localhost:8081/v3/api-docs`
4. 导入后会有完整的API集合

**配置环境变量**:
```
baseUrl: http://localhost:8081
token: YOUR_JWT_TOKEN
```

### 6.3 使用Chrome DevTools

**Network面板**:
- 查看HTTP请求/响应
- 检查请求头（Authorization）
- 查看响应状态码

**WebSocket面板**:
- 打开 DevTools → WS
- 查看WebSocket消息
- 查看连接状态

---

## 七、前后端联调流程

### 7.1 标准流程

```
1. 后端启动（./gradlew bootRun）
2. 前端启动（npm run dev）
3. 前端访问登录页面
4. 输入手机号获取验证码
5. 查看后端日志获取验证码
6. 前端输入验证码登录
7. 前端选择用户角色（盲人/志愿者）
8. 前端建立WebSocket连接
9. 开始功能测试
```

### 7.2 功能测试清单

#### 盲人用户功能
- [ ] 登录
- [ ] 设置紧急联系人
- [ ] 创建订单
- [ ] 查看订单列表
- [ ] 取消订单
- [ ] 评价订单
- [ ] 查看档案

#### 志愿者用户功能
- [ ] 登录
- [ ] 完善个人档案
- [ ] 上报位置
- [ ] 查看可用订单
- [ ] 接单
- [ ] 确认出发
- [ ] 确认到达
- [ ] 完成服务

#### 实时通信
- [ ] WebSocket连接成功
- [ ] 接收新订单推送
- [ ] 接收状态变更通知
- [ ] 心跳保活
- [ ] 断线重连

---

## 八、生产环境对接

### 8.1 生产环境API地址

```env
# .env.production
VITE_API_BASE_URL=https://api.yourdomain.com
VITE_WS_URL=wss://api.yourdomain.com/ws/volunteer
```

### 8.2 HTTPS配置

生产环境必须使用WSS（WebSocket Secure）:
```javascript
const wsUrl = import.meta.env.VITE_WS_URL.replace('ws://', 'wss://');
```

---

## 九、快速参考

### 常用curl命令

```bash
# 盲人登录
POST /api/auth/send-code
POST /api/auth/verify-code
POST /api/user/role

# 志愿者登录
POST /api/auth/send-code
POST /api/auth/verify-code
POST /api/user/role

# 创建订单
POST /api/orders

# 接单
POST /api/orders/{id}/accept

# WebSocket
WS /ws/volunteer?token=XXX
```

### 端口说明

| 服务 | 地址 | 说明 |
|------|------|------|
| 后端API | `http://localhost:8081` | REST API |
| Swagger UI | `http://localhost:8081/swagger-ui/index.html` | API文档 |
| WebSocket | `ws://localhost:8081/ws/volunteer` | 实时通信 |
| MySQL | `localhost:3306` | 数据库 |
| Redis | `localhost:6379` | 缓存 |

### 联调检查清单

#### 后端准备
- [ ] MySQL服务运行中
- [ ] Redis服务运行中
- [ ] 后端服务启动成功（8081端口）
- [ ] Swagger UI可访问
- [ ] 测试账号已创建

#### 前端准备
- [ ] API_BASE_URL已配置
- **[ ] JWT token存储机制已实现**
- **[ ] Authorization请求头已配置**
- [ ] WebSocket连接已实现
- [ ] 401自动跳转登录已实现
- [ ] 错误处理已实现

#### 联调测试
- [ ] 盲人用户可以登录
- [ ] 志愿者用户可以登录
- [ ] 盲人可以创建订单
- [ ] 志愿者可以接单
- [ ] WebSocket连接正常
- [ ] 订单状态推送正常
- [ ] 紧急求助功能正常

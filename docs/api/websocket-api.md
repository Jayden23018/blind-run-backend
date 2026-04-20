# WebSocket API文档

## 概述

WebSocket用于实时推送订单、通知、紧急求助等信息。所有WebSocket消息都是JSON格式。

## 连接方式

### 连接URL

```
ws://localhost:8081/ws/volunteer?token=<JWT_TOKEN>
```

**生产环境（WSS）**:
```
wss://your-domain.com/ws/volunteer?token=<JWT_TOKEN>
```

### 认证方式

- **Token位置**: URL查询参数 `?token=`
- **Token类型**: JWT token
- **验证时机**: 握手阶段
- **失败处理**: 连接被拒绝，HTTP 401

### 连接示例（JavaScript）

```javascript
const token = localStorage.getItem('jwtToken');
const ws = new WebSocket(`ws://localhost:8081/ws/volunteer?token=${token}`);

ws.onopen = () => {
  console.log('WebSocket connected');
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  console.log('Received:', message);
  handleMessage(message);
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};

ws.onclose = () => {
  console.log('WebSocket disconnected');
};
```

## 消息类型

### 1. 订单推送

**触发时机**: 新订单推送给志愿者

**消息格式**:
```json
{
  "type": "NEW_ORDER",
  "data": {
    "orderId": 123,
    "blindName": "李明",
    "startAddress": "朝阳公园南门",
    "plannedStartTime": "2026-04-15T14:00:00",
    "plannedEndTime": "2026-04-15T15:00:00"
  }
}
```

### 2. 通知推送

**触发时机**: 订单状态变更、系统通知

**消息格式**:
```json
{
  "type": "NOTIFICATION",
  "data": {
    "eventType": "ORDER_ACCEPTED",
    "message": "志愿者张三已接单",
    "ttsText": "志愿者张三已接单，请注意接听",
    "priority": "HIGH"
  }
}
```

**优先级**:
- **HIGH**: 紧急通知、志愿者到达
- **NORMAL**: 常规通知

### 3. 紧急求助推送

**触发时机**: 盲人用户触发紧急求助

**消息格式**:
```json
{
  "type": "EMERGENCY",
  "data": {
    "eventId": 456,
    "orderId": 123,
    "message": "用户触发紧急求助",
    "gpsLat": 39.9042,
    "gpsLng": 116.4074
  }
}
```

### 4. 系统消息

**触发时机**: 系统公告、维护通知

**消息格式**:
```json
{
  "type": "SYSTEM",
  "data": {
    "message": "系统将于今晚22:00进行维护",
    "timestamp": "2026-04-15T18:00:00"
  }
}
```

## 心跳保活

### 客户端心跳

建议客户端每30秒发送一次ping：

```javascript
setInterval(() => {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'PING' }));
  }
}, 30000);
```

### 服务端响应

服务端收到PING后会回复PONG：

```json
{
  "type": "PONG"
}
```

## 连接断开处理

### 正常关闭

```javascript
ws.close(1000, '正常关闭');
```

### 异常断线

```javascript
ws.onerror = (error) => {
  console.error('WebSocket error:', error);
  // 实现重连逻辑
  setTimeout(reconnect, 5000);
};

function reconnect() {
  const token = localStorage.getItem('jwtToken');
  ws = new WebSocket(`ws://localhost:8081/ws/volunteer?token=${token}`);
}
```

### 断线重连策略

1. **立即重连**: 第一次断线立即重连
2. **指数退避**: 重连失败后，等待时间指数增长（5s, 10s, 20s, 40s）
3. **最大间隔**: 最大等待时间不超过60秒
4. **手动刷新**: 连续失败5次后提示用户刷新页面

## 会话管理

### 多角色支持

同一用户可以同时以盲人和志愿者身份连接：

```javascript
// 盲人连接
const blindWs = new WebSocket(`ws://localhost:8081/ws/volunteer?token=${token}&role=BLIND`);

// 志愿者连接
const volunteerWs = new WebSocket(`ws://localhost:8081/ws/volunteer?token=${token}&role=VOLUNTEER`);
```

### 连接限制

- 每个用户每个角色最多1个活跃连接
- 新连接会替换旧连接
- 旧连接会收到关闭通知

## 消息路由

### 推送规则

| 消息类型 | 目标用户 | 说明 |
|---------|---------|------|
| NEW_ORDER | 志愿者 | 推送给附近志愿者 |
| ORDER_ACCEPTED | 盲人用户 | 订单被接受 |
| EMERGENCY | 志愿者、客服 | 紧急求助 |
| NOTIFICATION | 指定用户 | 个人通知 |

### 消息过滤

客户端可以过滤接收的消息类型：

```javascript
ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  
  switch(message.type) {
    case 'NEW_ORDER':
      handleNewOrder(message.data);
      break;
    case 'EMERGENCY':
      handleEmergency(message.data);
      break;
    case 'NOTIFICATION':
      if (message.data.priority === 'HIGH') {
        showNotification(message.data);
      }
      break;
  }
};
```

## 错误处理

### 连接失败

**401 Unauthorized**:
- 原因: Token无效或过期
- 处理: 重新登录获取新token

**403 Forbidden**:
- 原因: 无WebSocket权限
- 处理: 检查用户角色和权限

### 消息格式错误

```javascript
try {
  const message = JSON.parse(event.data);
} catch (error) {
  console.error('Invalid message format:', event.data);
}
```

## 注意事项

1. **Token过期**: Token过期后需重新获取，WebSocket连接不会自动续期
2. **网络切换**: 网络切换（WiFi ↔ 4G）会导致连接断开，需实现重连
3. **消息顺序**: 不保证消息严格顺序，需处理乱序情况
4. **服务器负载**: 大量并发连接可能影响性能，需控制连接数
5. **HTTPS**: 生产环境必须使用WSS（WebSocket Secure）

## 测试工具

### 在线测试工具

- WebSocket Tester: `http://www.websocket-test.com/`
- 在线WebSocket客户端: `https://www.piesocket.com/websocket-tester`

### curl测试（握手）

```bash
curl -i -N \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==" \
  http://localhost:8081/ws/volunteer?token=YOUR_JWT_TOKEN
```

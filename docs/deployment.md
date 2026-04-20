# 部署指南

## 环境要求

### 软件版本

| 组件 | 版本要求 | 推荐版本 |
|------|---------|---------|
| JDK | 17+ | 17.0.9 |
| MySQL | 8.x | 8.0.32 |
| Redis | 6.x | 6.2.7 |
| Gradle | 8.x | 8.5 |

### 硬件要求

**最小配置**:
- CPU: 2核
- 内存: 4GB
- 磁盘: 20GB

**推荐配置**:
- CPU: 4核
- 内存: 8GB
- 磁盘: 50GB SSD

## 本地开发启动

### 1. 环境准备

**安装JDK 17**:
```bash
# macOS
brew install openjdk@17

# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# Windows
# 下载并安装 https://adoptium.net/
```

**安装MySQL**:
```bash
# macOS
brew install mysql
brew services start mysql

# Ubuntu/Debian
sudo apt install mysql-server
sudo systemctl start mysql

# 创建数据库
mysql -u root -p
CREATE DATABASE spring_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**安装Redis**:
```bash
# macOS
brew install redis
brew services start redis

# Ubuntu/Debian
sudo apt install redis-server
sudo systemctl start redis
```

### 2. 配置文件

编辑 `src/main/resources/application.properties`:

```properties
# 数据库配置
spring.datasource.url=jdbc:mysql://localhost:3306/spring_demo
spring.datasource.username=root
spring.datasource.password=your_password

# Redis配置
spring.data.redis.host=localhost
spring.data.redis.port=6379

# JWT密钥（生产环境必须替换）
jwt.secret=your-secret-key-at-least-256-bits-long
jwt.expiration=86400000

# 文件上传目录（使用绝对路径）
app.upload.dir=/path/to/upload/dir
```

### 3. 编译运行

```bash
# 编译
./gradlew compileJava

# 运行测试
./gradlew test

# 启动服务
./gradlew bootRun
```

服务启动后访问: `http://localhost:8081`

## 生产环境部署

### 1. 服务器准备

**更新系统**:
```bash
sudo apt update && sudo apt upgrade -y
```

**安装JDK**:
```bash
sudo apt install openjdk-17-jdk -y
java -version
```

**安装MySQL**:
```bash
sudo apt install mysql-server -y
sudo mysql_secure_installation
```

**安装Redis**:
```bash
sudo apt install redis-server -y
sudo systemctl enable redis
```

### 2. 数据库初始化

```bash
# 创建数据库
mysql -u root -p
CREATE DATABASE spring_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'blindrun'@'localhost' IDENTIFIED BY 'strong_password';
GRANT ALL PRIVILEGES ON spring_demo.* TO 'blindrun'@'localhost';
FLUSH PRIVILEGES;
```

### 3. 应用部署

**上传应用包**:
```bash
# 打包
./gradlew clean build

# 生成的jar文件: build/libs/demo-0.0.1-SNAPSHOT.jar
scp build/libs/demo-0.0.1-SNAPSHOT.jar user@server:/opt/blindrun/
```

**创建systemd服务**:
```bash
sudo vim /etc/systemd/system/blindrun.service
```

内容:
```ini
[Unit]
Description=Blind Run Backend Service
After=network.target mysql.service redis.service

[Service]
Type=simple
User=blindrun
WorkingDirectory=/opt/blindrun
ExecStart=/usr/bin/java -jar -Xmx2g -Xms2g /opt/blindrun/demo-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10

EnvironmentFile=/opt/blindrun/application.properties

[Install]
WantedBy=multi-user.target
```

**启动服务**:
```bash
sudo systemctl daemon-reload
sudo systemctl enable blindrun
sudo systemctl start blindrun
sudo systemctl status blindrun
```

### 4. Nginx反向代理

**安装Nginx**:
```bash
sudo apt install nginx -y
```

**配置文件**:
```bash
sudo vim /etc/nginx/sites-available/blindrun
```

内容:
```nginx
server {
    listen 80;
    server_name your-domain.com;

    # HTTP重定向到HTTPS
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    # SSL证书配置
    ssl_certificate /etc/ssl/certs/your-domain.crt;
    ssl_certificate_key /etc/ssl/private/your-domain.key;

    # WebSocket代理
    location /ws/ {
        proxy_pass http://localhost:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }

    # API代理
    location /api/ {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**启用配置**:
```bash
sudo ln -s /etc/nginx/sites-available/blindrun /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### 5. SSL证书

**使用Let's Encrypt**:
```bash
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d your-domain.com
```

## 配置注意事项

### 必须修改的配置

1. **JWT密钥**: 生产环境必须使用强随机密钥
```properties
jwt.secret=your-very-strong-random-secret-key-at-least-256-bits-long-for-production
```

2. **数据库密码**: 使用强密码
```properties
spring.datasource.password=very-strong-password
```

3. **上传目录**: 使用绝对路径
```properties
app.upload.dir=/var/www/blindrun/uploads
```

### 阿里云配置

**隐私号服务**（如需启用）:
```properties
aliyun.private-number.enabled=true
aliyun.private-number.access-key-id=your-access-key-id
aliyun.private-number.access-key-secret=your-access-key-secret
aliyun.private-number.pool-key=your-pool-key
```

### 限流配置

根据实际流量调整:
```properties
rate-limit.general.max-requests=100
rate-limit.general.window-seconds=60
```

## 监控和日志

### 日志配置

**配置logback**:
```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/blindrun/spring.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/blindrun/spring-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="FILE" />
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

### 监控指标

**应用监控**:
- JVM内存使用
- CPU使用率
- 请求响应时间
- 错误率

**数据库监控**:
- 连接池使用情况
- 慢查询日志
- 死锁检测

**Redis监控**:
- 内存使用
- 连接数
- 命令执行次数

## 备份策略

### 数据库备份

**每日备份**:
```bash
# 添加到crontab
0 2 * * * /usr/bin/mysqldump -u blindrun -p'password' spring_demo | gzip > /backup/blindrun_$(date +\%Y\%m\%d).sql.gz
```

### Redis备份

**启用RDB**:
```bash
# /etc/redis/redis.conf
save 900 1
save 300 10
save 60 10000
```

## 故障排查

### 常见问题

**1. 应用无法启动**
- 检查Java版本: `java -version`
- 检查端口占用: `netstat -tulpn | grep 8081`
- 查看日志: `journalctl -u blindrun -f`

**2. 数据库连接失败**
- 检查MySQL状态: `sudo systemctl status mysql`
- 测试连接: `mysql -u blindrun -p`
- 检查防火墙: `sudo ufw status`

**3. WebSocket连接失败**
- 检查Nginx配置: `sudo nginx -t`
- 检查SSL证书: `sudo certbot certificates`
- 查看浏览器控制台错误信息

### 日志位置

- **应用日志**: `/var/log/blindrun/spring.log`
- **systemd日志**: `journalctl -u blindrun`
- **Nginx日志**: `/var/log/nginx/access.log`

## 安全加固

1. **使用HTTPS**: 强制所有通信使用SSL/TLS
2. **防火墙规则**: 只开放必要端口
3. **定期更新**: 保持系统和依赖包最新
4. **强密码策略**: 数据库和JWT使用强密码
5. **限制访问**: 数据库只允许本地连接
6. **日志审计**: 记录所有关键操作

## 性能优化

1. **数据库索引**: 为常用查询字段添加索引
2. **连接池调优**: 根据并发量调整连接池大小
3. **缓存策略**: 合理使用Redis缓存热点数据
4. **JVM调优**: 根据服务器内存调整JVM参数
5. **CDN加速**: 静态资源使用CDN分发

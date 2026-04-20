# 阿里云号码认证服务配置指南

## 概述

本项目使用阿里云**号码认证服务**（Dypnsapi）的 `SendSmsVerifyCode` API 发送短信验证码。
- SDK：`alibabacloud-dypnsapi20170525:2.0.0`
- 赠送签名：`速通互联验证码`（系统提供，免审核）
- 赠送模板：`100001`（系统提供，免审核）

**注意**：这是**号码认证服务**（Dypnsapi），不是**短信服务**（Dysmsapi），两者是不同的产品。

---

## 一、阿里云控制台配置

### 1.1 开通服务

1. 登录 [阿里云控制台](https://console.aliyun.com/)
2. 搜索"**号码认证服务**"并开通
3. 进入控制台 → **短信认证服务** → **短信认证参数管理**
4. 在 **签名配置** 页签确认赠送签名可用（如 `速通互联验证码`）
5. 在 **模板配置** 页签确认赠送模板可用（如 `100001`）

### 1.2 创建 AccessKey

1. 控制台右上角头像 → **AccessKey 管理**
2. 创建 RAM 子账号，添加权限：`AliyunDypnsFullAccess`
3. 创建 AccessKey，保存 ID 和 Secret

---

## 二、项目配置

### 2.1 application.properties

```properties
# 启用阿里云短信服务
sms.provider=aliyun

# AccessKey（开发环境可直接配置，生产环境建议用环境变量）
aliyun.credentials.access-key-id=你的AccessKeyId
aliyun.credentials.access-key-secret=你的AccessKeySecret

# 赠送签名（必须用 Unicode 转义，见下方说明）
aliyun.sms.sign-name=\u901F\u901A\u4E92\u8054\u9A8C\u8BC1\u7801

# 赠送模板
aliyun.sms.template-code=100001
```

### ⚠️ 重要：中文必须用 Unicode 转义

**Gradle `bootRun` 加载 `application.properties` 时使用 ISO-8859-1 编码**，中文会变成乱码导致 `isv.INVALID_PARAMETERS: 签名或者模版无效`。

```
# ❌ 错误 — 中文会被乱码
aliyun.sms.sign-name=速通互联验证码

# ✅ 正确 — Unicode 转义
aliyun.sms.sign-name=\u901F\u901A\u4E92\u8054\u9A8C\u8BC1\u7801
```

常用签名 Unicode 转义：

| 签名名称 | Unicode 转义 |
|---------|-------------|
| 速通互联验证码 | `\u901F\u901A\u4E92\u8054\u9A8C\u8BC1\u7801` |
| 速通互联验证平台 | `\u901F\u901A\u4E92\u8054\u9A8C\u8BC1\u5E73\u53F0` |
| 速通互联验证服务 | `\u901F\u901A\u4E92\u8054\u9A8C\u8BC1\u670D\u52A1` |
| 云渚科技验证平台 | `\u4E91\u6E1A\u79D1\u6280\u9A8C\u8BC1\u5E73\u53F0` |
| 云渚科技验证服务 | `\u4E91\u6E1A\u79D1\u6280\u9A8C\u8BC1\u670D\u52A1` |

### 2.2 配置项说明

| 配置项 | 说明 | 示例值 |
|--------|------|--------|
| `sms.provider` | `aliyun`=阿里云短信（默认） | `aliyun` |
| `aliyun.credentials.access-key-id` | AccessKey ID | `LTAI5txxx` |
| `aliyun.credentials.access-key-secret` | AccessKey Secret | `xxxxxx` |
| `aliyun.sms.sign-name` | 赠送签名（Unicode 转义） | `\u901F\u901A...` |
| `aliyun.sms.template-code` | 赠送模板 | `100001` |

---

## 三、测试

```bash
# 启动应用
./gradlew bootRun

# 发送验证码
curl -X POST http://127.0.0.1:8081/api/auth/send-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "你的手机号"}'

# 验证码登录
curl -X POST http://127.0.0.1:8081/api/auth/verify-code \
  -H "Content-Type: application/json" \
  -d '{"phone": "你的手机号", "code": "收到的验证码"}'
```

---

## 四、常见问题

### `isv.INVALID_PARAMETERS: 签名或者模版无效`

最常见原因：**签名中文字符乱码**。确保 `application.properties` 中 signName 使用 Unicode 转义（见第二节）。

### `No credential found`

AccessKey 未正确配置。检查 `application.properties` 中的 `aliyun.credentials.access-key-id` 和 `aliyun.credentials.access-key-secret`。

### `FREQUENCY_FAIL: 频控校验未通过`

同一手机号发送过于频繁。默认 60 秒间隔。

### `BUSINESS_LIMIT_CONTROL: 触发号码天级流控`

同一手机号当天发送次数超限。

---

## 五、架构说明

| 文件 | 说明 |
|------|------|
| `SmsService.java` | 短信服务接口 |
| `AliyunSmsServiceImpl.java` | 阿里云实现（默认激活） |
| `VerificationCodeService.java` | 验证码生成/Redis 存储/校验 |
| `AuthController.java` | 认证接口（调用短信服务） |

SDK 配置：
- Region：`cn-hangzhou`
- Endpoint：`dypnsapi.aliyuncs.com`
- Client：`AsyncClient` + `StaticCredentialProvider`
- `credentials-java` 无需显式依赖，由 SDK 传递引入

---

**文档版本**：2.0
**最后更新**：2026-04-14

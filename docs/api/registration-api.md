# 志愿者注册API文档

## 概述

志愿者注册流程包含4个步骤，必须按顺序完成。所有接口都需要JWT token认证（志愿者角色）。

## 注册流程

```
STEP_1: 基本信息 → STEP_2: 身份证 → STEP_3: 人脸 → STEP_4: 培训 → 完成
```

### 1. 获取注册状态

**接口**: `GET /api/volunteer/registration/status`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "currentStep": "STEP_2_ID_UPLOAD",
    "canAcceptOrders": false,
    "details": {
      "idVerifyStatus": "PENDING",
      "faceVerifyStatus": "NOT_STARTED",
      "totalTrainingMinutes": 0,
      "completedCoursesCount": 0,
      "currentCourseId": null
    }
  }
}
```

### 2. 提交基本信息（STEP_1 → STEP_2）

**接口**: `POST /api/volunteer/registration/step1`

**认证**: 需要JWT token（志愿者）

**请求体**:
```json
{
  "name": "张三",
  "phone": "13800138000"
}
```

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": "基本信息已提交"
}
```

**失败响应**:
- **409 Conflict** - 步骤不正确
```json
{
  "success": false,
  "code": 409,
  "message": "当前步骤不允许提交基本信息，当前步骤：STEP_2_ID_UPLOAD"
}
```

### 3. 上传身份证（STEP_2 → STEP_3）

**接口**: `POST /api/volunteer/registration/step2/id-card`

**认证**: 需要JWT token（志愿者）

**Content-Type**: `multipart/form-data`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| idCardName | string | 是 | 身份证姓名 |
| idCardNumber | string | 是 | 身份证号（18位） |
| frontFile | File | 是 | 身份证正面照片 |
| backFile | File | 是 | 身份证背面照片 |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": "身份证已上传，等待管理员审核"
}
```

**业务逻辑**:
- 身份证号格式校验（18位，最后一位可为X）
- 文件大小 ≤ 10MB
- 上传后状态变为PENDING，等待管理员审核
- 审核通过后进入STEP_3

### 4. 初始化人脸验证（STEP_3 → STEP_4）

**接口**: `POST /api/volunteer/registration/step3/face-verify/init`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "status": "NOT_AVAILABLE",
    "message": "当前阶段人脸验证为自动通过"
  }
}
```

**注意**: 当前为Stub实现，直接自动通过。待接入真实人脸识别系统。

## 培训系统

### 5. 获取培训课程列表

**接口**: `GET /api/volunteer/registration/training/courses`

**认证**: 需要JWT token（志愿者）

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "title": "志愿服务基础规范",
      "description": "了解志愿服务的基本规范和注意事项",
      "durationMinutes": 15,
      "displayOrder": 1,
      "isActive": true
    },
    {
      "id": 2,
      "title": "盲人陪跑服务指南",
      "description": "学习如何安全、专业地提供陪跑服务",
      "durationMinutes": 20,
      "displayOrder": 2,
      "isActive": true
    }
  ]
}
```

### 6. 提交学习进度

**接口**: `POST /api/volunteer/registration/training/progress`

**认证**: 需要JWT token（志愿者）

**请求体**:
```json
{
  "courseId": 1,
  "progressPercent": 50,
  "timeSpentSeconds": 450,
  "lastPositionSeconds": 300
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | number | 是 | 课程ID |
| progressPercent | number | 是 | 进度百分比（0-100） |
| timeSpentSeconds | number | 是 | 累计学习时长（秒） |
| lastPositionSeconds | number | 是 | 最后播放位置（秒） |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": "进度已更新"
}
```

**防作弊机制**:
- 必须按顺序学习课程
- 进度不能倒退
- 进度增长速度限制（每分钟最多10%）
- 进度达到100%才能进行测验

### 7. 获取测验题目

**接口**: `GET /api/volunteer/registration/training/quiz/{courseId}`

**认证**: 需要JWT token（志愿者）

**路径参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | number | 是 | 课程ID |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "questionText": "志愿者接单后应在多少分钟内联系盲人用户？",
      "questionType": "SINGLE_CHOICE",
      "options": ["5分钟内", "10分钟内", "15分钟内", "30分钟内"],
      "displayOrder": 1
    }
  ]
}
```

**注意**: 题目顺序每次随机，防止作弊。

### 8. 提交测验答案

**接口**: `POST /api/volunteer/registration/training/quiz/answer`

**认证**: 需要JWT token（志愿者）

**请求体**:
```json
{
  "courseId": 1,
  "questionId": 1,
  "userAnswer": ["5分钟内"],
  "timeSpentSeconds": 30
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| courseId | number | 是 | 课程ID |
| questionId | number | 是 | 题目ID |
| userAnswer | array | 是 | 用户答案 |
| timeSpentSeconds | number | 是 | 答题时长（秒） |

**成功响应** (200 OK):
```json
{
  "success": true,
  "code": 200,
  "message": "success",
  "data": {
    "questionId": 1,
    "isCorrect": true,
    "correctAnswer": ["5分钟内"],
    "explanation": "及时沟通是服务质量的关键",
    "timeSpentSeconds": 30
  }
}
```

**业务逻辑**:
- 题目不能重复作答
- 允许多次重测，取最高分
- 答对60%以上通过
- 完成所有必修课程后自动进入STEP_4_COMPLETED

## 错误码说明

| HTTP状态码 | 错误类型 | 说明 |
|-----------|---------|------|
| 400 | Bad Request | 参数校验失败 |
| 409 | Conflict | 步骤顺序错误、课程未完成 |

## 注意事项

1. **步骤顺序**: 必须按STEP_1 → STEP_2 → STEP_3 → STEP_4顺序完成
2. **身份证审核**: 由管理员审核，通过后才能进行人脸验证
3. **培训必修**: 必须完成所有必修课程并通过测验
4. **防作弊**: 进度速度限制、题目随机、禁止重复作答
5. **自动完成**: 完成所有课程后自动进入STEP_4_COMPLETED，可以接单

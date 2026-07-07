package com.example.demo.dto.volunteer;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 动作活体认证结果响应。
 * status: APPROVED（通过）/ REJECTED（失败，可重试）/ PENDING（进行中，前端继续轮询）
 */
@Data
@AllArgsConstructor
public class FaceVerifyResultResponse {
    private boolean passed;
    private String status;
    private String message;
}

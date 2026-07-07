package com.example.demo.service.impl;

import com.example.demo.service.FaceVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 动作活体认证测试桩。
 *
 * 仅在 {@code face-verify.provider=test} 时装配，用 @Primary 覆盖 {@link AliyunIdVerifyService}
 * 对 {@link FaceVerifyService} 接口的实现（AliyunIdVerifyService 始终装配，供 verifyIdCard 使用）。
 *
 * certifyId 协议：后缀决定结果
 *   - xxxxx_PASS    → 认证通过
 *   - xxxxx_REJECT  → 认证失败（可重试）
 *   - 其他          → 进行中（PENDING，前端继续轮询）
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "face-verify.provider", havingValue = "test")
public class TestFaceVerifyServiceImpl implements FaceVerifyService {

    private static final String TEST_CERTIFY_URL = "https://test.example.com/face-verify?certifyId=";

    @Override
    public FaceVerifyInitResult initFaceVerify(String certName, String certNo, String metaInfo,
                                               String returnUrl, String outerOrderNo) {
        String certifyId = UUID.randomUUID().toString().replace("-", "");
        log.info("[TestFaceVerify] initFaceVerify: certifyId={}（测试桩，需在 certifyId 末尾追加 _PASS/_REJECT 控制结果）", certifyId);
        return new FaceVerifyInitResult(certifyId, TEST_CERTIFY_URL + certifyId, "已发起（测试桩）");
    }

    @Override
    public FaceVerifyResult describeFaceVerify(String certifyId) {
        boolean passed = certifyId != null && certifyId.endsWith("_PASS");
        boolean rejected = certifyId != null && certifyId.endsWith("_REJECT");

        if (passed) {
            log.info("[TestFaceVerify] describeFaceVerify: certifyId={} → 通过", certifyId);
            return new FaceVerifyResult(true, "200", "认证通过（测试桩）");
        }
        if (rejected) {
            log.info("[TestFaceVerify] describeFaceVerify: certifyId={} → 拒绝", certifyId);
            return new FaceVerifyResult(false, "205", "活体检测存在风险（测试桩）");
        }
        log.info("[TestFaceVerify] describeFaceVerify: certifyId={} → 进行中", certifyId);
        return new FaceVerifyResult(false, "PENDING", "认证进行中（测试桩）");
    }
}

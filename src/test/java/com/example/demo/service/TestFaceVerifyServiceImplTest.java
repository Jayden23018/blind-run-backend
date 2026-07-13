package com.example.demo.service;

import com.example.demo.service.FaceVerifyService.FaceVerifyInitResult;
import com.example.demo.service.FaceVerifyService.FaceVerifyResult;
import com.example.demo.service.impl.TestFaceVerifyServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作活体测试桩的 certifyId 协议单元测试。
 *
 * 协议：certifyId 后缀决定结果
 *   _PASS → 通过、_REJECT → 失败、其他 → 进行中(PENDING)。
 */
class TestFaceVerifyServiceImplTest {

    private final TestFaceVerifyServiceImpl stub = new TestFaceVerifyServiceImpl();

    @Test
    @DisplayName("init 返回非空 certifyId + 包含该 id 的 certifyUrl")
    void initFaceVerify_returnsCertifyIdAndUrl() {
        FaceVerifyInitResult result = stub.initFaceVerify(
                "20", "张三", "110101199001011234", "{\"device\":\"ios\"}",
                "https://example.com/cb", "outer-1");

        assertThat(result.getCertifyId()).isNotBlank();
        assertThat(result.getCertifyUrl()).contains(result.getCertifyId());
        assertThat(result.getMessage()).isNotNull();
    }

    @Test
    @DisplayName("certifyId 以 _PASS 结尾 → 通过")
    void describe_passSuffix_passed() {
        FaceVerifyResult result = stub.describeFaceVerify("abc123_PASS");

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSubCode()).isEqualTo("200");
    }

    @Test
    @DisplayName("certifyId 以 _REJECT 结尾 → 失败")
    void describe_rejectSuffix_failed() {
        FaceVerifyResult result = stub.describeFaceVerify("abc123_REJECT");

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSubCode()).isEqualTo("205");
    }

    @Test
    @DisplayName("certifyId 无后缀 → 进行中（前端应继续轮询）")
    void describe_pendingSuffix_staysPending() {
        FaceVerifyResult result = stub.describeFaceVerify("abc123_pending");

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSubCode()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("certifyId 为 null → 进行中（不抛异常）")
    void describe_nullCertifyId_staysPending() {
        FaceVerifyResult result = stub.describeFaceVerify(null);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSubCode()).isEqualTo("PENDING");
    }
}

package com.example.demo.service;

/**
 * 人脸活体认证服务接口。
 *
 * 志愿者注册 step3 用阿里云动作活体（InitFaceVerify / DescribeFaceVerify）。
 * 测试环境走 {@code face-verify.provider=test} 的 stub 实现。
 * 盲人端的二要素核验（Id2Meta）不在此接口内，仍直接用 {@link AliyunIdVerifyService}。
 */
public interface FaceVerifyService {

    /**
     * 发起动作活体认证，返回 certifyId + 前端要打开的 CertifyUrl。
     *
     * @param certName    身份证姓名
     * @param certNo      身份证号码
     * @param metaInfo    前端设备指纹（阿里云 metaInfo，JSON 字符串）
     * @param returnUrl   动作活体完成后前端回跳地址（可选）
     * @param outerOrderNo 业务侧订单号（用于阿里云侧幂等）
     */
    FaceVerifyInitResult initFaceVerify(String certName, String certNo, String metaInfo,
                                        String returnUrl, String outerOrderNo);

    /**
     * 按 certifyId 拉取认证结果。
     */
    FaceVerifyResult describeFaceVerify(String certifyId);

    /** init 返回值 */
    class FaceVerifyInitResult {
        private final String certifyId;
        private final String certifyUrl;
        private final String message;

        public FaceVerifyInitResult(String certifyId, String certifyUrl, String message) {
            this.certifyId = certifyId;
            this.certifyUrl = certifyUrl;
            this.message = message;
        }

        public String getCertifyId() { return certifyId; }
        public String getCertifyUrl() { return certifyUrl; }
        public String getMessage() { return message; }
    }

    /** describe 返回值 */
    class FaceVerifyResult {
        private final boolean passed;
        private final String subCode;
        private final String message;

        public FaceVerifyResult(boolean passed, String subCode, String message) {
            this.passed = passed;
            this.subCode = subCode;
            this.message = message;
        }

        public boolean isPassed() { return passed; }
        public String getSubCode() { return subCode; }
        public String getMessage() { return message; }
    }
}

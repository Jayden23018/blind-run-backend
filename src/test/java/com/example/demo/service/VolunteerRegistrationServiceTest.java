package com.example.demo.service;

import com.example.demo.dto.volunteer.BasicInfoRequest;
import com.example.demo.dto.volunteer.FaceVerifyInitRequest;
import com.example.demo.dto.volunteer.FaceVerifyInitResponse;
import com.example.demo.dto.volunteer.FaceVerifyResultRequest;
import com.example.demo.dto.volunteer.FaceVerifyResultResponse;
import com.example.demo.entity.FaceVerifyStatus;
import com.example.demo.entity.IdVerifyStatus;
import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.VolunteerProfile;
import com.example.demo.exception.RegistrationStepException;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.service.FaceVerifyService.FaceVerifyInitResult;
import com.example.demo.service.FaceVerifyService.FaceVerifyResult;
import com.example.demo.service.impl.AliyunIdVerifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 志愿者注册流程单元测试（mock 5 个依赖）。
 *
 * 覆盖：step1 身份证核验通过/失败、init/query 状态机、历史 STEP_2 自动迁移、certifyId 越权防护。
 */
@ExtendWith(MockitoExtension.class)
class VolunteerRegistrationServiceTest {

    private static final String RETURN_URL = "https://app.example.com/face-verify/cb";
    private static final Long USER_ID = 1001L;

    @Mock private VolunteerProfileRepository volunteerProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private AliyunIdVerifyService idVerifyService;
    @Mock private FaceVerifyService faceVerifyService;

    private VolunteerRegistrationService service;

    // DTO 工厂（FaceVerifyInitRequest / FaceVerifyResultRequest 是 @Data，无构造器）
    private static FaceVerifyInitRequest initReq(String meta) {
        FaceVerifyInitRequest r = new FaceVerifyInitRequest();
        r.setMetaInfo(meta);
        return r;
    }
    private static FaceVerifyResultRequest resultReq(String certifyId) {
        FaceVerifyResultRequest r = new FaceVerifyResultRequest();
        r.setCertifyId(certifyId);
        return r;
    }

    @BeforeEach
    void setUp() {
        service = new VolunteerRegistrationService(
                volunteerProfileRepository, userRepository, notificationService,
                idVerifyService, faceVerifyService);
        ReflectionTestUtils.setField(service, "faceVerifyReturnUrl", RETURN_URL);
    }

    // === step1 ===

    @Test
    @DisplayName("submitBasicInfo 身份证核验通过 → APPROVED + 推进到 STEP_3")
    void submitBasicInfo_idVerifyPassed_advancesToStep3() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_1_BASIC_INFO);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(idVerifyService.verifyIdCard("张三", "110101199001011234")).thenReturn(true);

        BasicInfoRequest req = basicInfo("张三", "110101199001011234");
        service.submitBasicInfo(USER_ID, req);

        assertThat(profile.getIdVerifyStatus()).isEqualTo(IdVerifyStatus.APPROVED);
        assertThat(profile.getIdVerifyRejectionReason()).isNull();
        assertThat(profile.getIdCardName()).isEqualTo("张三");
        assertThat(profile.getIdCardNumber()).isEqualTo("110101199001011234");
        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_3_FACE_VERIFY);
    }

    @Test
    @DisplayName("submitBasicInfo 身份证核验失败 → REJECTED + 仍推进到 STEP_3（前端可改信息重提）")
    void submitBasicInfo_idVerifyRejected_stillAdvancesButMarkedRejected() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_1_BASIC_INFO);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(idVerifyService.verifyIdCard(anyString(), anyString())).thenReturn(false);

        service.submitBasicInfo(USER_ID, basicInfo("李四", "110101199001011234"));

        assertThat(profile.getIdVerifyStatus()).isEqualTo(IdVerifyStatus.REJECTED);
        assertThat(profile.getIdVerifyRejectionReason()).isNotNull();
        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_3_FACE_VERIFY);
    }

    @Test
    @DisplayName("submitBasicInfo 非 STEP_1 状态 → 抛 RegistrationStepException")
    void submitBasicInfo_wrongStep_throws() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.submitBasicInfo(USER_ID, basicInfo("张三", "110101199001011234")))
                .isInstanceOf(RegistrationStepException.class);
    }

    // === init ===

    @Test
    @DisplayName("initFaceVerify 成功 → 存 certifyId + faceVerifyStatus=PENDING")
    void initFaceVerify_success_storesCertifyId() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setIdVerifyStatus(IdVerifyStatus.APPROVED);
        profile.setIdCardName("张三");
        profile.setIdCardNumber("110101199001011234");
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(faceVerifyService.initFaceVerify(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FaceVerifyInitResult("cid-1", "https://aliyun/cid-1", "ok"));

        FaceVerifyInitResponse resp = service.initFaceVerify(USER_ID, initReq("{}"));

        assertThat(resp.getCertifyId()).isEqualTo("cid-1");
        assertThat(resp.getCertifyUrl()).contains("cid-1");
        assertThat(profile.getFaceVerifyCertifyId()).isEqualTo("cid-1");
        assertThat(profile.getFaceVerifyStatus()).isEqualTo(FaceVerifyStatus.PENDING);
    }

    @Test
    @DisplayName("initFaceVerify 身份证 REJECTED → 拒绝发起")
    void initFaceVerify_idRejected_throws() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setIdVerifyStatus(IdVerifyStatus.REJECTED);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.initFaceVerify(USER_ID, initReq("{}")))
                .isInstanceOf(RegistrationStepException.class);
        verifyNoInteractions(faceVerifyService);
    }

    @Test
    @DisplayName("initFaceVerify 返回 null certifyId → status=ERROR，不落库 certifyId")
    void initFaceVerify_nullCertifyId_returnsError() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setIdVerifyStatus(IdVerifyStatus.APPROVED);
        profile.setIdCardName("张三");
        profile.setIdCardNumber("110101199001011234");
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(faceVerifyService.initFaceVerify(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FaceVerifyInitResult(null, null, "阿里云错误"));

        FaceVerifyInitResponse resp = service.initFaceVerify(USER_ID, initReq("{}"));

        assertThat(resp.getStatus()).isEqualTo("ERROR");
        assertThat(profile.getFaceVerifyCertifyId()).isNull();
    }

    // === query result ===

    @Test
    @DisplayName("queryFaceVerifyResult 通过 → APPROVED + 推进 STEP_4_TRAINING")
    void queryResult_passed_advancesToTraining() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setFaceVerifyCertifyId("cid-pass");
        profile.setFaceVerifyStatus(FaceVerifyStatus.PENDING);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(faceVerifyService.describeFaceVerify("cid-pass"))
                .thenReturn(new FaceVerifyResult(true, "200", "通过"));

        FaceVerifyResultResponse resp = service.queryFaceVerifyResult(USER_ID, resultReq("cid-pass"));

        assertThat(resp.isPassed()).isTrue();
        assertThat(resp.getStatus()).isEqualTo("APPROVED");
        assertThat(profile.getFaceVerifyStatus()).isEqualTo(FaceVerifyStatus.APPROVED);
        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_4_TRAINING);
    }

    @Test
    @DisplayName("queryFaceVerifyResult 进行中 → 保持 PENDING，状态不变")
    void queryResult_pending_staysPending() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setFaceVerifyCertifyId("cid-pending");
        profile.setFaceVerifyStatus(FaceVerifyStatus.PENDING);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(faceVerifyService.describeFaceVerify("cid-pending"))
                .thenReturn(new FaceVerifyResult(false, "PENDING", "进行中"));

        FaceVerifyResultResponse resp = service.queryFaceVerifyResult(USER_ID, resultReq("cid-pending"));

        assertThat(resp.isPassed()).isFalse();
        assertThat(resp.getStatus()).isEqualTo("PENDING");
        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_3_FACE_VERIFY);
    }

    @Test
    @DisplayName("queryFaceVerifyResult 明确失败 → REJECTED，可重新 init")
    void queryResult_rejected_marksRejected() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setFaceVerifyCertifyId("cid-rej");
        profile.setFaceVerifyStatus(FaceVerifyStatus.PENDING);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(faceVerifyService.describeFaceVerify("cid-rej"))
                .thenReturn(new FaceVerifyResult(false, "205", "活体风险"));

        FaceVerifyResultResponse resp = service.queryFaceVerifyResult(USER_ID, resultReq("cid-rej"));

        assertThat(resp.getStatus()).isEqualTo("REJECTED");
        assertThat(profile.getFaceVerifyStatus()).isEqualTo(FaceVerifyStatus.REJECTED);
        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_3_FACE_VERIFY);
    }

    @Test
    @DisplayName("queryFaceVerifyResult 幂等：已 APPROVED 重复查询不报错、不调 describeFaceVerify")
    void queryResult_alreadyApproved_idempotent() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_4_TRAINING);
        profile.setFaceVerifyCertifyId("cid-pass");
        profile.setFaceVerifyStatus(FaceVerifyStatus.APPROVED);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        FaceVerifyResultResponse resp = service.queryFaceVerifyResult(USER_ID, resultReq("cid-pass"));

        assertThat(resp.isPassed()).isTrue();
        verifyNoInteractions(faceVerifyService);
    }

    @Test
    @DisplayName("queryFaceVerifyResult 越权：certifyId 不匹配 → 抛 RegistrationStepException")
    void queryResult_certifyIdMismatch_throws() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_3_FACE_VERIFY);
        profile.setFaceVerifyCertifyId("cid-real");
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.queryFaceVerifyResult(USER_ID, resultReq("cid-forged")))
                .isInstanceOf(RegistrationStepException.class);
        verifyNoInteractions(faceVerifyService);
    }

    // === 历史 STEP_2 自动迁移 ===

    @Test
    @DisplayName("getRegistrationStatus 历史 STEP_2 → 自动迁移到 STEP_3 并写回")
    void status_legacyStep2_migratesToStep3() {
        VolunteerProfile profile = profileAt(RegistrationStep.STEP_2_ID_UPLOAD);
        when(volunteerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(volunteerProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getRegistrationStatus(USER_ID);

        assertThat(profile.getRegistrationStep()).isEqualTo(RegistrationStep.STEP_3_FACE_VERIFY);
        verify(volunteerProfileRepository).save(profile);
    }

    // === helpers ===

    private VolunteerProfile profileAt(RegistrationStep step) {
        VolunteerProfile p = new VolunteerProfile();
        p.setUserId(USER_ID);
        p.setRegistrationStep(step);
        return p;
    }

    private BasicInfoRequest basicInfo(String name, String idCard) {
        BasicInfoRequest r = new BasicInfoRequest();
        r.setName(name);
        r.setPhone("13800138000");
        r.setIdCardName(name);
        r.setIdCardNumber(idCard);
        return r;
    }
}

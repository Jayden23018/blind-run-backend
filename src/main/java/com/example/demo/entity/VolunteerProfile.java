package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者资料实体 —— 对应数据库中的 volunteer_profile 表
 */
@Data
@Entity
@Table(name = "volunteer_profile")
public class VolunteerProfile {

    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    private String name;

    /** 是否已认证 */
    @Column(nullable = false)
    private Boolean verified = false;

    /** 认证状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 16, nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.NONE;

    /** 认证证件文件路径 */
    @Column(name = "verification_doc_url", length = 500)
    private String verificationDocUrl;

    /** 注册步骤 */
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_step", length = 32, nullable = false)
    private RegistrationStep registrationStep = RegistrationStep.STEP_1_BASIC_INFO;

    /** 手机号 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 身份证号 */
    @Column(name = "id_card_number", length = 18)
    private String idCardNumber;

    /** 身份证姓名 */
    @Column(name = "id_card_name", length = 50)
    private String idCardName;

    /** 身份证正面照片URL */
    @Column(name = "id_card_front_url", length = 500)
    private String idCardFrontUrl;

    /** 身份证背面照片URL */
    @Column(name = "id_card_back_url", length = 500)
    private String idCardBackUrl;

    /** 人脸照片URL */
    @Column(name = "face_photo_url", length = 500)
    private String facePhotoUrl;

    /** 身份证验证状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "id_verify_status", length = 16, nullable = false)
    private IdVerifyStatus idVerifyStatus = IdVerifyStatus.NOT_STARTED;

    /** 人脸验证状态（系统自动处理） */
    @Enumerated(EnumType.STRING)
    @Column(name = "face_verify_status", length = 16, nullable = false)
    private FaceVerifyStatus faceVerifyStatus = FaceVerifyStatus.NOT_STARTED;

    /** 总培训时长（分钟） */
    @Column(name = "total_training_minutes")
    private Integer totalTrainingMinutes = 0;

    /** 已完成课程数 */
    @Column(name = "completed_courses_count")
    private Integer completedCoursesCount = 0;

    /** 当前正在学习的课程ID */
    @Column(name = "current_course_id")
    private Long currentCourseId;

    /** 跑步经验 */
    @Column(name = "running_experience", columnDefinition = "TEXT")
    private String runningExperience;

    /** 是否有陪跑经验 */
    @Column(name = "has_guided_before", nullable = false)
    private Boolean hasGuidedBefore = false;

    /** 紧急情况处理经验 */
    @Column(name = "emergency_experience", columnDefinition = "TEXT")
    private String emergencyExperience;

    /** 身份证审核拒绝原因 */
    @Column(name = "id_verify_rejection_reason", length = 500)
    private String idVerifyRejectionReason;

    /** 人脸验证拒绝原因（保留字段，当前不使用） */
    @Column(name = "face_verify_rejection_reason", length = 500)
    private String faceVerifyRejectionReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

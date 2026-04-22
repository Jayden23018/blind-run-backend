package com.example.demo.service;

import com.example.demo.dto.volunteer.QuestionResult;
import com.example.demo.dto.volunteer.QuizAnswerRequest;
import com.example.demo.dto.volunteer.QuizQuestionResponse;
import com.example.demo.dto.volunteer.QuizResultResponse;
import com.example.demo.dto.volunteer.TrainingCourseResponse;
import com.example.demo.dto.volunteer.TrainingProgressRequest;
import com.example.demo.entity.QuestionType;
import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.TargetRole;
import com.example.demo.entity.TrainingCourse;
import com.example.demo.entity.TrainingProgress;
import com.example.demo.entity.TrainingProgressStatus;
import com.example.demo.entity.TrainingQuizAttempt;
import com.example.demo.entity.TrainingQuizQuestion;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.exception.TrainingException;
import com.example.demo.repository.TrainingCourseRepository;
import com.example.demo.repository.TrainingProgressRepository;
import com.example.demo.repository.TrainingQuizAttemptRepository;
import com.example.demo.repository.TrainingQuizQuestionRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 培训业务服务
 */
@Slf4j
@Service
public class TrainingService {

    private final TrainingCourseRepository courseRepository;
    private final TrainingProgressRepository progressRepository;
    private final TrainingQuizQuestionRepository questionRepository;
    private final TrainingQuizAttemptRepository attemptRepository;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public TrainingService(TrainingCourseRepository courseRepository,
                          TrainingProgressRepository progressRepository,
                          TrainingQuizQuestionRepository questionRepository,
                          TrainingQuizAttemptRepository attemptRepository,
                          VolunteerProfileRepository volunteerProfileRepository,
                          NotificationService notificationService,
                          ObjectMapper objectMapper) {
        this.courseRepository = courseRepository;
        this.progressRepository = progressRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取课程列表
     */
    public List<TrainingCourseResponse> getCourses(Long userId) {
        List<TrainingCourse> courses = courseRepository.findByIsActiveTrueOrderByDisplayOrderAsc();

        Map<Long, TrainingProgress> progressMap = progressRepository
                .findByVolunteerIdOrderByCourseId(userId)
                .stream()
                .collect(Collectors.toMap(TrainingProgress::getCourseId, p -> p));

        return courses.stream().map(course -> {
            TrainingProgress progress = progressMap.get(course.getId());
            TrainingProgressStatus status = progress != null ? progress.getStatus() : TrainingProgressStatus.NOT_STARTED;
            int progressPercent = progress != null ? progress.getProgressPercent() : 0;
            boolean isCompleted = status == TrainingProgressStatus.COMPLETED;

            return new TrainingCourseResponse(
                    course.getId(),
                    course.getTitle(),
                    course.getDescription(),
                    course.getDurationMinutes(),
                    course.getVideoUrl(),
                    course.getContent(),
                    course.getDisplayOrder(),
                    true, // 所有课程都是必修
                    status,
                    progressPercent,
                    isCompleted
            );
        }).collect(Collectors.toList());
    }

    /**
     * 提交学习进度（含防作弊校验）
     */
    @Transactional
    public void submitProgress(Long userId, TrainingProgressRequest request) {
        // 校验课程存在且激活
        TrainingCourse course = courseRepository.findByIdAndIsActiveTrue(request.getCourseId())
                .orElseThrow(() -> new TrainingException("课程不存在"));

        // 获取志愿者资料，确认注册步骤
        var profileOpt = volunteerProfileRepository.findByUserId(userId);
        if (profileOpt.isEmpty()) {
            throw new TrainingException("志愿者资料不存在");
        }
        var profile = profileOpt.get();

        // 校验：必须在 STEP_4_TRAINING
        if (profile.getRegistrationStep() != RegistrationStep.STEP_4_TRAINING &&
            profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
            throw new TrainingException("请先完成前面的注册步骤");
        }

        TrainingProgress progress = progressRepository
                .findByVolunteerIdAndCourseId(userId, request.getCourseId())
                .orElse(null);

        if (progress == null) {
            // 第一次学习：检查是否完成前置课程
            if (course.getDisplayOrder() > 1) {
                var previousCourse = courseRepository.findByDisplayOrder(course.getDisplayOrder() - 1);
                if (previousCourse.isPresent()) {
                    var prevProgress = progressRepository
                            .findByVolunteerIdAndCourseId(userId, previousCourse.get().getId());
                    if (prevProgress.isEmpty() ||
                        prevProgress.get().getStatus() != TrainingProgressStatus.COMPLETED) {
                        throw new TrainingException("请先完成前置课程");
                    }
                }
            }

            // 创建新进度记录
            progress = new TrainingProgress();
            progress.setVolunteerId(userId);
            progress.setCourseId(request.getCourseId());
            progress.setStatus(TrainingProgressStatus.IN_PROGRESS);
        } else {
            // 校验进度递增
            if (request.getProgressPercent() <= progress.getProgressPercent() &&
                request.getProgressPercent() < 100) {
                throw new TrainingException("进度不能倒退");
            }

            // 校验时间合理性：进度增加不能太快
            int progressDelta = request.getProgressPercent() - progress.getProgressPercent();
            int timeDeltaSeconds = request.getTimeSpentSeconds() - progress.getTimeSpentSeconds();

            if (progressDelta > 0 && timeDeltaSeconds > 0) {
                // 每分钟最多看10%，允许10倍误差
                double maxProgressPerSecond = 10.0 / 60.0; // 每秒0.167%
                double actualProgressPerSecond = (double) progressDelta / timeDeltaSeconds;
                if (actualProgressPerSecond > maxProgressPerSecond * 10) {
                    throw new TrainingException("进度提交异常，请正常观看视频");
                }
            }
        }

        // 更新进度
        progress.setProgressPercent(request.getProgressPercent());
        progress.setLastPositionSeconds(request.getLastPositionSeconds());
        progress.setTimeSpentSeconds(request.getTimeSpentSeconds());

        if (request.getProgressPercent() >= 100) {
            progress.setStatus(TrainingProgressStatus.COMPLETED);
            progress.setCompletedAt(LocalDateTime.now());
        }

        progressRepository.save(progress);

        // 更新志愿者当前课程
        profile.setCurrentCourseId(request.getCourseId());
        volunteerProfileRepository.save(profile);

        log.info("志愿者 {} 提交课程 {} 进度：{}%", userId, request.getCourseId(), request.getProgressPercent());
    }

    /**
     * 获取测验题目
     */
    public List<QuizQuestionResponse> getQuizQuestions(Long userId, Long courseId) {
        // 校验课程已完成（100%进度）
        TrainingProgress progress = progressRepository.findByVolunteerIdAndCourseId(userId, courseId)
                .orElseThrow(() -> new TrainingException("请先学习课程"));

        if (progress.getProgressPercent() < 95) {
            throw new TrainingException("课程学习未完成，不能进行测验");
        }

        List<TrainingQuizQuestion> questions = questionRepository.findByCourseIdOrderByDisplayOrderAsc(courseId);

        // 随机打乱题目顺序
        java.util.Collections.shuffle(questions);

        return questions.stream().map(q -> {
            try {
                List<String> options = objectMapper.readValue(q.getOptions(), new TypeReference<List<String>>() {});

                // 随机打乱选项顺序
                List<String> shuffledOptions = new ArrayList<>(options);
                java.util.Collections.shuffle(shuffledOptions);

                // 统计该题已答题次数
                int attemptCount = (int) attemptRepository.countByVolunteerIdAndQuestionId(userId, q.getId());

                return new QuizQuestionResponse(
                        q.getId(),
                        q.getCourseId(),
                        q.getQuestionText(),
                        q.getQuestionType().name(),
                        shuffledOptions,
                        attemptCount,
                        q.getDisplayOrder()
                );
            } catch (Exception e) {
                log.error("解析题目选项失败：{}", q.getOptions(), e);
                throw new TrainingException("题目数据格式错误");
            }
        }).collect(Collectors.toList());
    }

    /**
     * 提交测验答案（允许重复作答）
     */
    @Transactional
    public QuizResultResponse submitQuizAnswer(Long userId, QuizAnswerRequest request) {
        // 校验课程已完成
        TrainingProgress progress = progressRepository.findByVolunteerIdAndCourseId(userId, request.getCourseId())
                .orElseThrow(() -> new TrainingException("请先学习课程"));

        if (progress.getProgressPercent() < 95) {
            throw new TrainingException("课程学习未完成，不能进行测验");
        }

        // 获取题目
        TrainingQuizQuestion question = questionRepository.findById(request.getQuestionId())
                .orElseThrow(() -> new TrainingException("题目不存在"));

        // 校验题目属于该课程
        if (!question.getCourseId().equals(request.getCourseId())) {
            throw new TrainingException("题目不属于该课程");
        }

        // 判题
        try {
            List<String> correctAnswers = objectMapper.readValue(question.getCorrectAnswer(),
                    new TypeReference<List<String>>() {});
            Set<String> correctSet = new HashSet<>(correctAnswers);
            Set<String> userSet = new HashSet<>(request.getAnswers());
            boolean isCorrect = correctSet.equals(userSet);

            // 记录答题（允许重复作答）
            TrainingQuizAttempt attempt = new TrainingQuizAttempt();
            attempt.setVolunteerId(userId);
            attempt.setCourseId(request.getCourseId());
            attempt.setQuestionId(request.getQuestionId());
            attempt.setUserAnswer(objectMapper.writeValueAsString(request.getAnswers()));
            attempt.setIsCorrect(isCorrect);
            attempt.setTimeSpentSeconds(request.getTimeSpentSeconds());
            attemptRepository.save(attempt);

            log.info("志愿者 {} 答题：courseId={}, questionId={}, correct={}",
                    userId, request.getCourseId(), request.getQuestionId(), isCorrect);

            // 计算测验结果（取最高分）
            return buildQuizResult(userId, request.getCourseId());

        } catch (Exception e) {
            log.error("判题失败", e);
            throw new TrainingException("判题失败");
        }
    }

    /**
     * 检查是否完成所有必修课程
     */
    @Transactional
    public void checkAllCoursesCompleted(Long userId) {
        long totalCourses = courseRepository.findByIsActiveTrueOrderByDisplayOrderAsc().stream().count();
        long completedCourses = progressRepository.countByVolunteerIdAndStatus(userId, TrainingProgressStatus.COMPLETED);

        if (completedCourses >= totalCourses) {
            // 所有必修课程完成
            var profileOpt = volunteerProfileRepository.findByUserId(userId);
            if (profileOpt.isPresent()) {
                var profile = profileOpt.get();
                if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
                    profile.setRegistrationStep(RegistrationStep.STEP_4_COMPLETED);
                    profile.setVerified(true);

                    // 统计总培训时长
                    List<TrainingProgress> allProgress = progressRepository.findByVolunteerIdOrderByCourseId(userId);
                    int totalMinutes = allProgress.stream()
                            .mapToInt(p -> p.getTimeSpentSeconds() / 60)
                            .sum();
                    profile.setTotalTrainingMinutes(totalMinutes);
                    profile.setCompletedCoursesCount((int) completedCourses);

                    volunteerProfileRepository.save(profile);

                    // 发送完成通知
                    notificationService.sendNotification(userId, "TRAINING_COMPLETED",
                            TargetRole.VOLUNTEER, null);

                    log.info("志愿者 {} 完成所有培训课程，可以接单", userId);
                }
            }
        }
    }

    // === 私有方法 ===

    private QuizResultResponse buildQuizResult(Long userId, Long courseId) {
        List<TrainingQuizQuestion> allQuestions = questionRepository.findByCourseIdOrderByDisplayOrderAsc(courseId);
        List<TrainingQuizAttempt> userAttempts = attemptRepository
                .findByVolunteerIdAndCourseIdOrderByAttemptedAtDesc(userId, courseId);

        // 按题目ID分组，取最高分（即最新的一次正确答案）
        Map<Long, TrainingQuizAttempt> bestAttempts = new HashMap<>();
        for (TrainingQuizAttempt attempt : userAttempts) {
            bestAttempts.put(attempt.getQuestionId(), attempt);
        }

        int correctCount = 0;
        List<QuestionResult> questionResults = new ArrayList<>();

        for (TrainingQuizQuestion question : allQuestions) {
            TrainingQuizAttempt attempt = bestAttempts.get(question.getId());
            boolean isCorrect = attempt != null && attempt.getIsCorrect();
            if (isCorrect) {
                correctCount++;
            }

            questionResults.add(new QuestionResult(
                    question.getId(),
                    question.getQuestionText(),
                    isCorrect,
                    question.getExplanation()
            ));
        }

        int totalQuestions = allQuestions.size();
        int scorePercent = totalQuestions > 0 ? (correctCount * 100 / totalQuestions) : 0;
        boolean passed = scorePercent >= 60; // 60分及格

        return new QuizResultResponse(
                passed,
                correctCount,
                totalQuestions,
                scorePercent,
                questionResults,
                -1 // -1 表示无限次答题机会
        );
    }
}

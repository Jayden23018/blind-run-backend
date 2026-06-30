package com.example.demo.service;

import com.example.demo.dto.VolunteerAvailableTimeSlot;
import com.example.demo.dto.VolunteerProfileResponse;
import com.example.demo.dto.VolunteerProfileUpdateRequest;
import com.example.demo.dto.volunteer.VolunteerDispatchActiveOrder;
import com.example.demo.dto.volunteer.VolunteerDispatchRecentOrder;
import com.example.demo.dto.volunteer.VolunteerDispatchSummaryResponse;
import com.example.demo.entity.DispatchBlockReason;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.OrderReview;
import com.example.demo.entity.RegistrationStep;
import com.example.demo.entity.RunOrder;
import com.example.demo.entity.User;
import com.example.demo.entity.UserRole;
import com.example.demo.entity.VerificationStatus;
import com.example.demo.entity.VolunteerAvailableTime;
import com.example.demo.entity.VolunteerProfile;
import com.example.demo.exception.PermissionDeniedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.OrderReviewRepository;
import com.example.demo.repository.RunOrderRepository;
import com.example.demo.repository.VolunteerAvailableTimeRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.util.PhoneMaskUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 志愿者业务逻辑服务
 */
@Service
public class VolunteerService {

    private final VolunteerProfileRepository volunteerProfileRepository;
    private final VolunteerAvailableTimeRepository volunteerAvailableTimeRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final DispatchService dispatchService;
    private final VolunteerLocationService volunteerLocationService;
    private final RunOrderRepository runOrderRepository;
    private final OrderReviewRepository orderReviewRepository;

    /** 可接单覆盖半径（km），取全局 app.matching.max-distance-km，默认 10 */
    @Value("${app.matching.max-distance-km:10}")
    private int coverageRadiusKm;

    public VolunteerService(VolunteerProfileRepository volunteerProfileRepository,
                            VolunteerAvailableTimeRepository volunteerAvailableTimeRepository,
                            UserRepository userRepository,
                            FileStorageService fileStorageService,
                            DispatchService dispatchService,
                            VolunteerLocationService volunteerLocationService,
                            RunOrderRepository runOrderRepository,
                            OrderReviewRepository orderReviewRepository) {
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.volunteerAvailableTimeRepository = volunteerAvailableTimeRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.dispatchService = dispatchService;
        this.volunteerLocationService = volunteerLocationService;
        this.runOrderRepository = runOrderRepository;
        this.orderReviewRepository = orderReviewRepository;
    }

    /**
     * 获取志愿者资料
     */
    public VolunteerProfileResponse getProfile(Long userId) {
        checkVolunteerRole(userId);

        VolunteerProfile profile = volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在"));

        List<VolunteerAvailableTime> times = volunteerAvailableTimeRepository.findByVolunteerId(userId);
        List<VolunteerAvailableTimeSlot> slots = times.stream().map(t -> {
            VolunteerAvailableTimeSlot slot = new VolunteerAvailableTimeSlot();
            slot.setDayOfWeek(t.getDayOfWeek());
            slot.setStartTime(t.getStartTime());
            slot.setEndTime(t.getEndTime());
            return slot;
        }).toList();

        return new VolunteerProfileResponse(
                profile.getName(),
                profile.getVerificationStatus().name(),
                slots,
                profile.getAcceptsGuideDog(),
                profile.getPaceRange(),
                profile.getWantsDispatch()
        );
    }

    /**
     * 志愿者首页聚合数据 —— 一次返回首页所需的全部信息（接单资格/在线位置/覆盖范围/时段/评分统计/订单）。
     *
     * 纯读操作，标 readOnly=true 既给 Hibernate 优化空间，又维持 session 触发 blindUser 懒加载
     * （近期记录 findByVolunteerId(Pageable) 不能加 JOIN FETCH，否则 Page count query 生成失败）。
     */
    @Transactional(readOnly = true)
    public VolunteerDispatchSummaryResponse getDispatchSummary(Long userId) {
        checkVolunteerRole(userId);

        VolunteerProfile profile = volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在"));

        // 1. 在线状态与最近位置（Redis，离线返回 null → 位置字段统一置 null）
        Map<String, Object> loc = volunteerLocationService.getVolunteerLocation(userId);
        boolean isOnline = loc != null;
        Double lastLat = isOnline ? (Double) loc.get("lat") : null;
        Double lastLng = isOnline ? (Double) loc.get("lng") : null;
        LocalDateTime lastLocationAt = null;
        if (isOnline && loc.get("updatedAt") instanceof Long epochMs) {
            lastLocationAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        }

        // 2. 不可接单原因 + canDispatch（4 条件独立评估，命中的全塞进 List）
        List<DispatchBlockReason> reasons = new ArrayList<>();
        if (!Boolean.TRUE.equals(profile.getWantsDispatch())) {
            reasons.add(DispatchBlockReason.DISPATCH_DISABLED);
        }
        if (!Boolean.TRUE.equals(profile.getVerified())) {
            reasons.add(DispatchBlockReason.NOT_VERIFIED);
        }
        if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
            reasons.add(DispatchBlockReason.REGISTRATION_INCOMPLETE);
        }
        if (!isOnline) {
            reasons.add(DispatchBlockReason.OFFLINE);
        }
        boolean canDispatch = reasons.isEmpty();

        // 3. 可服务时段模板 + 当前是否在可服务时段内
        List<VolunteerAvailableTime> times = volunteerAvailableTimeRepository.findByVolunteerId(userId);
        List<VolunteerAvailableTimeSlot> slots = times.stream().map(t -> {
            VolunteerAvailableTimeSlot slot = new VolunteerAvailableTimeSlot();
            slot.setDayOfWeek(t.getDayOfWeek());
            slot.setStartTime(t.getStartTime());
            slot.setEndTime(t.getEndTime());
            return slot;
        }).toList();
        boolean isWithinServiceTime = calcWithinServiceTime(times);

        // 4. 当前活跃订单（用 JOIN FETCH 方法避免 blindUser 懒加载异常）
        List<OrderStatus> activeStatuses = List.of(
                OrderStatus.IN_PROGRESS, OrderStatus.DRIVER_EN_ROUTE, OrderStatus.DRIVER_ARRIVED);
        List<RunOrder> activeEntities = runOrderRepository
                .findByVolunteerIdAndStatusInFetchBlind(userId, activeStatuses);
        List<VolunteerDispatchActiveOrder> activeOrders = activeEntities.stream()
                .map(this::toActiveOrder)
                .toList();

        // 5. 近期 5 条记录（按创建时间倒序）
        Page<RunOrder> recentPage = runOrderRepository.findByVolunteerId(
                userId, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<RunOrder> recentEntities = recentPage.getContent();

        // 6. N+1 防护：批量查近期记录的评价，构建 orderId → rating 映射
        List<Long> recentOrderIds = recentEntities.stream().map(RunOrder::getId).toList();
        Map<Long, Integer> ratingByOrderId = new HashMap<>();
        if (!recentOrderIds.isEmpty()) {
            for (OrderReview review : orderReviewRepository.findByOrderIdIn(recentOrderIds)) {
                ratingByOrderId.put(review.getOrderId(), review.getRating());
            }
        }
        List<VolunteerDispatchRecentOrder> recentOrders = recentEntities.stream()
                .map(o -> toRecentOrder(o, ratingByOrderId.get(o.getId())))
                .toList();

        return new VolunteerDispatchSummaryResponse(
                canDispatch,
                reasons,
                Boolean.TRUE.equals(profile.getWantsDispatch()),
                isOnline,
                lastLat,
                lastLng,
                lastLocationAt,
                coverageRadiusKm,
                isWithinServiceTime,
                slots,
                profile.getAvgRating(),
                profile.getTotalRatings() == null ? 0 : profile.getTotalRatings(),
                profile.getTotalDispatched() == null ? 0 : profile.getTotalDispatched(),
                profile.getTotalAccepted() == null ? 0 : profile.getTotalAccepted(),
                profile.getTotalCompleted() == null ? 0 : profile.getTotalCompleted(),
                profile.getTotalDeclined() == null ? 0 : profile.getTotalDeclined(),
                profile.getTotalTimeout() == null ? 0 : profile.getTotalTimeout(),
                profile.getAcceptanceRate(),
                activeOrders,
                recentOrders
        );
    }

    /** 计算当前时刻是否落在某个可服务时段内（按今天周几 + 当前时间） */
    private boolean calcWithinServiceTime(List<VolunteerAvailableTime> times) {
        if (times == null || times.isEmpty()) {
            return false;
        }
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        LocalTime now = LocalTime.now();
        return times.stream().anyMatch(t -> {
            try {
                return DayOfWeek.valueOf(t.getDayOfWeek().toUpperCase()) == today
                        && !now.isBefore(t.getStartTime())
                        && now.isBefore(t.getEndTime());
            } catch (IllegalArgumentException e) {
                // dayOfWeek 存了非标准值（如 "MON" 缩写），跳过该条
                return false;
            }
        });
    }

    /** RunOrder → 活跃订单精简 DTO（盲人手机号脱敏） */
    private VolunteerDispatchActiveOrder toActiveOrder(RunOrder o) {
        User blind = o.getBlindUser();
        return new VolunteerDispatchActiveOrder(
                o.getId(),
                o.getStatus().name(),
                o.getPlannedStartTime(),
                o.getPlannedEndTime(),
                o.getStartAddress(),
                blind.getName(),
                PhoneMaskUtils.mask(blind.getPhone()),
                o.getAcceptedAt()
        );
    }

    /** RunOrder → 近期记录精简 DTO（blindName 靠 @Transactional(readOnly=true) 维持 session 懒加载） */
    private VolunteerDispatchRecentOrder toRecentOrder(RunOrder o, Integer rating) {
        return new VolunteerDispatchRecentOrder(
                o.getId(),
                o.getStatus().name(),
                o.getPlannedStartTime(),
                o.getFinishedAt(),
                rating,
                o.getStartAddress(),
                o.getBlindUser().getName()
        );
    }

    /**
     * 更新志愿者资料（name + 可用时间段替换）
     */
    @Transactional
    public VolunteerProfileResponse updateProfile(Long userId, VolunteerProfileUpdateRequest request) {
        checkVolunteerRole(userId);

        VolunteerProfile profile = volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在"));

        profile.setName(request.getName());
        if (request.getAcceptsGuideDog() != null) profile.setAcceptsGuideDog(request.getAcceptsGuideDog());
        if (request.getPaceRange() != null) profile.setPaceRange(request.getPaceRange());
        if (request.getWantsDispatch() != null) {
            profile.setWantsDispatch(request.getWantsDispatch());
            // 已在下方 save 落库；此处仅同步 Redis 派单热路径缓存，保持与 DB 一致
            volunteerLocationService.syncWantsDispatchToRedis(userId, request.getWantsDispatch());
        }
        volunteerProfileRepository.save(profile);
        dispatchService.evictProfileCache(userId);

        // 替换可用时间段：先删后插
        volunteerAvailableTimeRepository.deleteByVolunteerId(userId);
        if (request.getAvailableTimeSlots() != null) {
            for (VolunteerAvailableTimeSlot slot : request.getAvailableTimeSlots()) {
                if (slot.getStartTime() != null && slot.getEndTime() != null
                        && !slot.getStartTime().isBefore(slot.getEndTime())) {
                    throw new IllegalArgumentException("时间段开始时间必须早于结束时间");
                }
                VolunteerAvailableTime time = new VolunteerAvailableTime();
                time.setVolunteerId(userId);
                time.setDayOfWeek(slot.getDayOfWeek());
                time.setStartTime(slot.getStartTime());
                time.setEndTime(slot.getEndTime());
                volunteerAvailableTimeRepository.save(time);
            }
        }

        return getProfile(userId);
    }

    /**
     * 上传资质证件 —— 提交后进入待审核状态，由管理员审核
     * 允许状态：NONE（首次）、PENDING（覆盖重传）、REJECTED（被拒后重传）
     * 不允许状态：APPROVED（已通过无需重传）
     */
    @Transactional
    public String submitVerification(Long userId, MultipartFile file) {
        checkVolunteerRole(userId);

        VolunteerProfile profile = volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在"));

        if (profile.getVerificationStatus() == VerificationStatus.APPROVED) {
            throw new IllegalArgumentException("资质证书已审核通过，无需重新上传");
        }

        String filePath = fileStorageService.store(file);

        profile.setVerificationDocUrl(filePath);
        profile.setVerificationStatus(VerificationStatus.PENDING);
        profile.setVerified(false); // 明确重置，防止状态不一致
        volunteerProfileRepository.save(profile);
        dispatchService.evictProfileCache(userId);

        return VerificationStatus.PENDING.name();
    }

    /**
     * 获取认证状态
     */
    public String getVerificationStatus(Long userId) {
        checkVolunteerRole(userId);

        VolunteerProfile profile = volunteerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("志愿者资料不存在"));

        return profile.getVerificationStatus().name();
    }

    private void checkVolunteerRole(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        if (user.getRole() != UserRole.VOLUNTEER) {
            throw new PermissionDeniedException("仅志愿者用户可访问此接口");
        }
    }
}

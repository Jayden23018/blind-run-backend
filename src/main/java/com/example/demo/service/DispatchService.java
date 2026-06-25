package com.example.demo.service;

import com.example.demo.dto.RespondAction;
import com.example.demo.dto.ScoredCandidate;
import com.example.demo.entity.*;
import com.example.demo.exception.OrderPermissionException;
import com.example.demo.exception.OrderStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.event.DispatchAcceptedEvent;
import com.example.demo.repository.RunOrderRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import com.example.demo.repository.VolunteerAvailableTimeRepository;
import com.example.demo.repository.VolunteerProfileRepository;
import com.example.demo.util.PhoneMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 串行派单服务 —— 核心协调器
 *
 * 职责：
 * 1. 订单创建后启动派单流程
 * 2. 维护 Redis 派单队列（有序候选志愿者）
 * 3. 逐个推送通知给志愿者，等待 30s 超时后推下一个
 * 4. 处理志愿者的接单/拒绝响应
 * 5. 队列耗尽时扩圈重新评分
 */
@Slf4j
@Service
public class DispatchService {

    private final ScoringService scoringService;
    private final VolunteerLocationService volunteerLocationService;
    private final VolunteerProfileRepository volunteerProfileRepository;
    private final VolunteerAvailableTimeRepository availableTimeRepository;
    private final RunOrderRepository runOrderRepository;
    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${app.dispatch.per-volunteer-timeout-seconds:30}")
    private int perVolunteerTimeoutSeconds;

    @Value("${app.dispatch.total-timeout-before-start-minutes:30}")
    private int totalTimeoutBeforeStartMinutes;

    @Value("${app.dispatch.round1-distance-km:5}")
    private double round1DistanceKm;

    @Value("${app.dispatch.round2-distance-km:10}")
    private double round2DistanceKm;

    @Value("${app.dispatch.round3-distance-km:20}")
    private double round3DistanceKm;

    @Value("${app.dispatch.round1-time-overlap:0.8}")
    private double round1TimeOverlap;

    @Value("${app.dispatch.round2-time-overlap:0.6}")
    private double round2TimeOverlap;

    public DispatchService(ScoringService scoringService,
                           VolunteerLocationService volunteerLocationService,
                           VolunteerProfileRepository volunteerProfileRepository,
                           VolunteerAvailableTimeRepository availableTimeRepository,
                           RunOrderRepository runOrderRepository,
                           StringRedisTemplate redisTemplate,
                           NotificationService notificationService,
                           ApplicationEventPublisher eventPublisher,
                           ObjectMapper objectMapper) {
        this.scoringService = scoringService;
        this.volunteerLocationService = volunteerLocationService;
        this.volunteerProfileRepository = volunteerProfileRepository;
        this.availableTimeRepository = availableTimeRepository;
        this.runOrderRepository = runOrderRepository;
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // ===== Redis Key 模板 =====
    public static final String QUEUE_KEY = "order:dispatch:queue:%d";
    public static final String CURRENT_KEY = "order:dispatch:current:%d";
    public static final String ROUND_KEY = "order:dispatch:round:%d";
    public static final String LOCK_KEY = "order:dispatch:lock:%d";

    // ===== 志愿者资料缓存 =====
    static final String PROFILE_CACHE_PREFIX = "vol:profile:";
    private static final long PROFILE_CACHE_TTL_MINUTES = 10;

    // ===== 公共 API =====

    /**
     * 启动派单流程（由 MatchingService 调用）
     */
    @Transactional
    public void initiateDispatch(RunOrder order) {
        if (order.getDispatchStartedAt() != null) {
            log.warn("订单 {} 已在派单流程中，跳过重复启动", order.getId());
            return;
        }

        order.setDispatchStartedAt(LocalDateTime.now());
        order.setDispatchRound(1);
        runOrderRepository.save(order);

        // A8-② 首次派单正向反馈：告知盲人已开始呼叫（参考滴滴"正在为您呼叫"节奏，不每志愿者打扰）
        notificationService.sendNotification(
                order.getBlindUser().getId(),
                "DISPATCH_STARTED", TargetRole.BLIND_USER, null);

        populateQueue(order, 1);
        dispatchToNext(order);
    }

    /**
     * 志愿者接单（由 OrderService 调用）
     */
    @Transactional
    public void handleAccept(Long orderId, Long volunteerId) {
        // 1. 校验 dispatch_current 匹配
        Long currentVolunteer = getCurrentVolunteer(orderId);
        if (currentVolunteer == null || !currentVolunteer.equals(volunteerId)) {
            throw new OrderStatusException("该订单当前未派送给您，无法接单");
        }

        // 2. 分布式锁防并发（TTL 30s，远超正常处理时间）
        String lockKey = String.format(LOCK_KEY, orderId);
        boolean locked = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, String.valueOf(volunteerId), 30, TimeUnit.SECONDS));
        if (!locked) {
            throw new OrderStatusException("订单正在处理中，请稍后重试");
        }

        try {
            int attempts = 0;
            while (true) {
                attempts++;
                try {
                    RunOrder order = runOrderRepository.findById(orderId)
                            .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));

                    if (order.getStatus() != OrderStatus.PENDING_MATCH) {
                        if (order.getStatus() == OrderStatus.PENDING_ACCEPT
                                || order.getStatus() == OrderStatus.IN_PROGRESS
                                || order.getStatus() == OrderStatus.DRIVER_EN_ROUTE
                                || order.getStatus() == OrderStatus.DRIVER_ARRIVED) {
                            throw new OrderPermissionException("ORDER_ALREADY_ACCEPTED", "订单已被其他志愿者接单");
                        }
                        throw new OrderStatusException("订单状态不允许接单: " + order.getStatus());
                    }

                    // 3. 更新订单状态（先保存 DB，成功后再清 Redis）
                    order.setStatus(OrderStatus.PENDING_ACCEPT);
                    order.setDispatchCurrentVolunteerId(null);
                    runOrderRepository.save(order);

                    // 4. 更新志愿者接单统计，并使 profile 缓存失效（统计数据已变）
                    updateAcceptStats(volunteerId);
                    evictProfileCache(volunteerId);

                    // 5. DB 保存成功，清理 Redis 派单状态
                    clearDispatchState(orderId);

                    long elapsed = order.getDispatchStartedAt() != null
                            ? java.time.Duration.between(order.getDispatchStartedAt(), LocalDateTime.now()).getSeconds()
                            : -1;
                    log.info("志愿者 {} 接受订单 {}，派单耗时: {}s", volunteerId, orderId, elapsed);
                    break;
                } catch (OptimisticLockingFailureException e) {
                    if (attempts >= 3) {
                        throw new OrderStatusException("订单并发冲突，请稍后重试");
                    }
                    log.warn("志愿者 {} 接单 {} 乐观锁冲突，第 {} 次重试", volunteerId, orderId, attempts);
                }
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 志愿者拒绝（由 OrderService 调用）
     */
    @Transactional
    public void handleDecline(Long orderId, Long volunteerId) {
        Long currentVolunteer = getCurrentVolunteer(orderId);
        if (currentVolunteer == null || !currentVolunteer.equals(volunteerId)) {
            throw new OrderStatusException("该订单当前未派送给您");
        }

        // 更新拒绝统计，并使 profile 缓存失效
        updateDeclineStats(volunteerId);
        evictProfileCache(volunteerId);

        // 清除当前派送
        redisTemplate.delete(String.format(CURRENT_KEY, orderId));

        RunOrder order = runOrderRepository.findById(orderId).orElse(null);
        if (order != null) {
            order.setDispatchCurrentVolunteerId(null);
            runOrderRepository.save(order);
        }

        log.info("志愿者 {} 拒绝订单 {}", volunteerId, orderId);

        // 推送给下一个候选人
        if (order != null) {
            dispatchToNext(order);
        }
    }

    /**
     * 志愿者超时未响应（由 DispatchScheduler 调用）
     */
    @Transactional
    public void handleVolunteerTimeout(Long orderId) {
        RunOrder order = runOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_MATCH) return;

        Long timedOutVolunteer = order.getDispatchCurrentVolunteerId();
        if (timedOutVolunteer != null) {
            updateTimeoutStats(timedOutVolunteer);
            evictProfileCache(timedOutVolunteer);
            log.info("志愿者 {} 对订单 {} 响应超时", timedOutVolunteer, orderId);
        }

        order.setDispatchCurrentVolunteerId(null);
        runOrderRepository.save(order);
        redisTemplate.delete(String.format(CURRENT_KEY, orderId));

        dispatchToNext(order);
    }

    /**
     * 志愿者响应串行派单（接单或拒绝）—— 由 OrderController 调用
     * ACCEPT：完成派单协议后发布 DispatchAcceptedEvent，异步推进订单状态机
     * DECLINE：直接走拒绝流程，推送下一个候选志愿者
     *
     * 注意：必须加 @Transactional，因为 handleAccept/handleDecline 是同类方法，
     * Spring AOP 内部调用不经过代理，其自身的 @Transactional 不会生效。
     */
    @Transactional
    public void handleVolunteerResponse(Long orderId, Long volunteerId, RespondAction action) {
        // 志愿者资质校验：未完成注册/认证的志愿者不应响应派单（与原 OrderLifecycleService.acceptOrder 检查一致）。
        // 派单虽只派给认证志愿者，但旧 /accept 与 /respond 共用此入口，显式校验才能给出友好的 403 反馈，
        // 而非被派单归属校验以"未派送给您"409 拒绝（对未认证用户体验更差）。
        VolunteerProfile profile = volunteerProfileRepository.findByUserId(volunteerId)
                .orElseThrow(() -> new OrderPermissionException("VOLUNTEER_NOT_VERIFIED", "请先完成志愿者认证"));
        if (profile.getRegistrationStep() != RegistrationStep.STEP_4_COMPLETED) {
            throw new OrderPermissionException("VOLUNTEER_NOT_REGISTERED",
                    "请先完成志愿者注册流程（当前步骤：" + profile.getRegistrationStep().name() + "）");
        }
        if (!Boolean.TRUE.equals(profile.getVerified())) {
            throw new OrderPermissionException("VOLUNTEER_NOT_VERIFIED", "请先完成志愿者认证");
        }
        if (!Boolean.TRUE.equals(profile.getIsAvailable())) {
            throw new OrderPermissionException("VOLUNTEER_NOT_AVAILABLE", "当前您已关闭可服务状态，请先开启后再接单");
        }
        switch (action) {
            case ACCEPT -> {
                handleAccept(orderId, volunteerId);
                eventPublisher.publishEvent(new DispatchAcceptedEvent(this, orderId, volunteerId));
            }
            case DECLINE -> handleDecline(orderId, volunteerId);
        }
    }

    /**
     * 获取当前正在等待回应的志愿者 ID
     */
    public Long getCurrentVolunteer(Long orderId) {
        String key = String.format(CURRENT_KEY, orderId);
        String val = redisTemplate.opsForValue().get(key);
        if (val != null) {
            try { return Long.parseLong(val); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ===== 内部方法 =====

    /**
     * 评分并填充 Redis 队列
     */
    void populateQueue(RunOrder order, int round) {
        double distanceKm = getDistanceForRound(round);
        double minOverlap = getOverlapForRound(round);

        // 1. GEO 查询：只取订单起点半径内的在线志愿者（替代全量 SCAN）
        List<Map<String, Object>> locations = volunteerLocationService
                .getVolunteersNear(order.getStartLatitude(), order.getStartLongitude(), distanceKm)
                .stream()
                .filter(loc -> !Boolean.FALSE.equals(loc.get("wantsDispatch")))
                .toList();
        if (locations.isEmpty()) {
            log.info("订单 {} 第 {} 轮：{}km 内无在线且接单中的志愿者", order.getId(), round, distanceKm);
            return;
        }

        // 2. 从缓存（优先）或 DB（兜底）加载志愿者资料
        Set<Long> volunteerIds = new HashSet<>();
        for (Map<String, Object> loc : locations) {
            Object id = loc.get("userId");
            if (id instanceof Number n) volunteerIds.add(n.longValue());
        }

        Map<Long, VolunteerProfile> profiles = loadProfilesCached(volunteerIds);

        List<VolunteerAvailableTime> allSlots = availableTimeRepository.findByVolunteerIdIn(volunteerIds);
        Map<Long, List<VolunteerAvailableTime>> availability = allSlots.stream()
                .collect(Collectors.groupingBy(VolunteerAvailableTime::getVolunteerId));

        // 3. 评分
        List<ScoredCandidate> candidates = scoringService.scoreCandidates(
                order, locations, profiles, availability, distanceKm, minOverlap);

        if (candidates.isEmpty()) {
            log.info("订单 {} 第 {} 轮：无符合条件候选者", order.getId(), round);
            return;
        }

        // 4. 写入 Redis 队列（最高分在左，LPOP 取最高分）
        String queueKey = String.format(QUEUE_KEY, order.getId());
        redisTemplate.delete(queueKey);
        for (ScoredCandidate c : candidates) {
            redisTemplate.opsForList().rightPush(queueKey, String.valueOf(c.volunteerId()));
        }

        // 设置 TTL：订单结束后 2 小时自动清理
        long ttlSeconds = 7200; // 2h
        redisTemplate.expire(queueKey, ttlSeconds, TimeUnit.SECONDS);

        // 存储轮次
        String roundKey = String.format(ROUND_KEY, order.getId());
        redisTemplate.opsForValue().set(roundKey, String.valueOf(round), ttlSeconds, TimeUnit.SECONDS);

        log.info("订单 {} 第 {} 轮：{} 名候选者入队（距离≤{}km，时间重叠≥{}%）",
                order.getId(), round, candidates.size(), (int) distanceKm, (int) (minOverlap * 100));
    }

    /**
     * 推送给队列中的下一个候选人
     */
    @Transactional
    public void dispatchToNext(RunOrder order) {
        if (order.getStatus() != OrderStatus.PENDING_MATCH) {
            log.debug("订单 {} 状态为 {}，不再派单", order.getId(), order.getStatus());
            return;
        }

        String queueKey = String.format(QUEUE_KEY, order.getId());
        String volunteerIdStr = redisTemplate.opsForList().leftPop(queueKey);

        if (volunteerIdStr == null) {
            // 队列耗尽，尝试扩圈
            tryExpandRound(order);
            return;
        }

        Long volunteerId = Long.parseLong(volunteerIdStr);

        // 设置当前派送志愿者（TTL = 超时时间）
        String currentKey = String.format(CURRENT_KEY, order.getId());
        redisTemplate.opsForValue().set(currentKey, volunteerIdStr, perVolunteerTimeoutSeconds, TimeUnit.SECONDS);

        // 镜像到 DB（崩溃恢复用）
        RunOrder dbOrder = runOrderRepository.findById(order.getId()).orElse(null);
        if (dbOrder != null) {
            dbOrder.setDispatchCurrentVolunteerId(volunteerId);
            runOrderRepository.save(dbOrder);
        }

        // 推送 WebSocket 通知
        pushOrderNotification(order, volunteerId);

        log.info("订单 {} 派送给志愿者 {}（{}s 超时）", order.getId(), volunteerId, perVolunteerTimeoutSeconds);
    }

    /**
     * 扩圈逻辑：队列耗尽时扩大搜索范围
     */
    @Transactional
    void tryExpandRound(RunOrder order) {
        // 检查总超时
        LocalDateTime deadline = order.getPlannedStartTime().minusMinutes(totalTimeoutBeforeStartMinutes);
        if (LocalDateTime.now().isAfter(deadline)) {
            handleNoMatch(order);
            return;
        }

        int currentRound = order.getDispatchRound() != null ? order.getDispatchRound() : 0;
        int nextRound = currentRound + 1;

        if (nextRound > 3) {
            handleNoMatch(order);
            return;
        }

        log.info("订单 {} 扩圈：第 {} 轮 → 第 {} 轮", order.getId(), currentRound, nextRound);

        // 通知盲人正在扩大搜索
        if (nextRound >= 2) {
            notificationService.sendNotification(
                    order.getBlindUser().getId(),
                    "DISPATCH_EXPANDING",
                    TargetRole.BLIND_USER,
                    Map.of("round", String.valueOf(nextRound))
            );
        }

        order.setDispatchRound(nextRound);
        runOrderRepository.save(order);

        populateQueue(order, nextRound);

        // 新队列可能也为空
        String queueKey = String.format(QUEUE_KEY, order.getId());
        Long queueSize = redisTemplate.opsForList().size(queueKey);
        if (queueSize == null || queueSize == 0) {
            // 再试一次扩圈（或最终超时）
            tryExpandRound(order);
            return;
        }

        dispatchToNext(order);
    }

    /**
     * 无匹配结果：进入全城广播求助（NEEDS_HELP）
     * 向所有在线且 wantsDispatch=true 的认证志愿者广播，任意一人可主动认领
     */
    @Transactional
    void handleNoMatch(RunOrder order) {
        order.setStatus(OrderStatus.NEEDS_HELP);
        order.setDispatchCurrentVolunteerId(null);
        runOrderRepository.save(order);
        clearDispatchState(order.getId());

        // 通知盲人：正在全城广播求助
        notificationService.sendAppNotification(
                order.getBlindUser().getId(),
                "暂时未能匹配到志愿者",
                "我们已向全城志愿者发出求助，如有志愿者方便会主动联系您，请耐心等待"
        );

        // 向全城在线志愿者广播
        broadcastHelpNeeded(order);

        log.info("订单 {} 派单失败，已进入全城广播求助，通知 {} 名在线志愿者",
                order.getId(), volunteerLocationService.getOnlineVolunteerLocations().size());
    }

    /** 向所有在线且接单中的志愿者广播求助 */
    private void broadcastHelpNeeded(RunOrder order) {
        List<Map<String, Object>> candidates = volunteerLocationService.getOnlineVolunteerLocations()
                .stream()
                .filter(loc -> !Boolean.FALSE.equals(loc.get("wantsDispatch")))
                .toList();

        for (Map<String, Object> loc : candidates) {
            Object id = loc.get("userId");
            if (id instanceof Number n) {
                notificationService.sendHelpBroadcastToVolunteer(n.longValue(), order);
            }
        }
        log.info("订单 {} 求助广播已推送给 {} 名在线志愿者", order.getId(), candidates.size());
    }

    // ===== 推送通知 =====

    private void pushOrderNotification(RunOrder order, Long volunteerId) {
        double distanceKm = 0;
        List<Map<String, Object>> locations = volunteerLocationService.getOnlineVolunteerLocations();
        for (Map<String, Object> loc : locations) {
            Object id = loc.get("userId");
            if (id instanceof Number n && n.longValue() == volunteerId) {
                double lat = ((Number) loc.get("lat")).doubleValue();
                double lng = ((Number) loc.get("lng")).doubleValue();
                distanceKm = com.example.demo.util.GeoUtils.distanceKm(
                        order.getStartLatitude(), order.getStartLongitude(), lat, lng);
                break;
            }
        }
        notificationService.sendDispatchNotification(volunteerId, order, distanceKm, perVolunteerTimeoutSeconds);
    }

    // ===== 统计更新 =====

    private void updateAcceptStats(Long volunteerId) {
        volunteerProfileRepository.atomicIncrementAcceptStats(volunteerId);
    }

    private void updateDeclineStats(Long volunteerId) {
        volunteerProfileRepository.atomicIncrementDeclineStats(volunteerId);
    }

    /** 超时统计（V1：独立于拒绝，不污染 acceptanceRate） */
    private void updateTimeoutStats(Long volunteerId) {
        volunteerProfileRepository.atomicIncrementTimeoutStats(volunteerId);
    }

    // ===== 工具方法 =====

    void clearDispatchState(Long orderId) {
        redisTemplate.delete(String.format(QUEUE_KEY, orderId));
        redisTemplate.delete(String.format(CURRENT_KEY, orderId));
        redisTemplate.delete(String.format(ROUND_KEY, orderId));
        redisTemplate.delete(String.format(LOCK_KEY, orderId));
    }

    /** 主动使指定志愿者的 profile 缓存失效（统计/资质更新后调用） */
    public void evictProfileCache(Long userId) {
        redisTemplate.delete(PROFILE_CACHE_PREFIX + userId);
    }

    /**
     * 批量加载志愿者资料：优先读 Redis 缓存，缓存未命中时批量查 DB 并回写缓存
     *
     * 策略：MGET 一次网络往返取全部缓存结果；只对 miss 的 ID 查一次 DB
     */
    private Map<Long, VolunteerProfile> loadProfilesCached(Set<Long> ids) {
        List<Long> idList = new ArrayList<>(ids);
        List<String> keys = idList.stream()
                .map(id -> PROFILE_CACHE_PREFIX + id)
                .collect(Collectors.toList());

        List<String> cachedJsons = redisTemplate.opsForValue().multiGet(keys);

        Map<Long, VolunteerProfile> result = new HashMap<>();
        Set<Long> misses = new HashSet<>();

        for (int i = 0; i < idList.size(); i++) {
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null) {
                VolunteerProfile p = profileFromJson(json);
                if (p != null) result.put(idList.get(i), p);
                else misses.add(idList.get(i));
            } else {
                misses.add(idList.get(i));
            }
        }

        if (!misses.isEmpty()) {
            volunteerProfileRepository.findByUserIdIn(misses).forEach(p -> {
                result.put(p.getUserId(), p);
                String json = profileToJson(p);
                if (json != null) {
                    redisTemplate.opsForValue().set(
                            PROFILE_CACHE_PREFIX + p.getUserId(), json,
                            PROFILE_CACHE_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                }
            });
        }

        return result;
    }

    /** 将评分所需字段序列化为 JSON（避免序列化 JPA 懒加载关联） */
    private String profileToJson(VolunteerProfile p) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("userId", p.getUserId());
            data.put("verified", p.getVerified());
            data.put("registrationStep", p.getRegistrationStep() != null ? p.getRegistrationStep().name() : null);
            data.put("acceptsGuideDog", p.getAcceptsGuideDog());
            data.put("avgRating", p.getAvgRating());
            data.put("totalRatings", p.getTotalRatings());
            data.put("totalDispatched", p.getTotalDispatched());
            data.put("totalAccepted", p.getTotalAccepted());
            data.put("totalDeclined", p.getTotalDeclined());
            data.put("acceptanceRate", p.getAcceptanceRate());
            data.put("paceRange", p.getPaceRange() != null ? p.getPaceRange().name() : null);
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("序列化 profile 失败 userId={}: {}", p.getUserId(), e.getMessage());
            return null;
        }
    }

    /** 从 JSON 反序列化出 VolunteerProfile（仅还原评分相关字段） */
    @SuppressWarnings("unchecked")
    private VolunteerProfile profileFromJson(String json) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            VolunteerProfile p = new VolunteerProfile();
            if (data.get("userId") instanceof Number n) p.setUserId(n.longValue());
            p.setVerified((Boolean) data.get("verified"));
            if (data.get("registrationStep") instanceof String s)
                p.setRegistrationStep(RegistrationStep.valueOf(s));
            p.setAcceptsGuideDog((Boolean) data.get("acceptsGuideDog"));
            if (data.get("avgRating") instanceof Number n) p.setAvgRating(n.doubleValue());
            if (data.get("totalRatings") instanceof Number n) p.setTotalRatings(n.intValue());
            if (data.get("totalDispatched") instanceof Number n) p.setTotalDispatched(n.intValue());
            if (data.get("totalAccepted") instanceof Number n) p.setTotalAccepted(n.intValue());
            if (data.get("totalDeclined") instanceof Number n) p.setTotalDeclined(n.intValue());
            if (data.get("acceptanceRate") instanceof Number n) p.setAcceptanceRate(n.doubleValue());
            if (data.get("paceRange") instanceof String s)
                p.setPaceRange(PacePreference.valueOf(s));
            return p;
        } catch (Exception e) {
            log.warn("反序列化 profile 缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private double getDistanceForRound(int round) {
        return switch (round) {
            case 1 -> round1DistanceKm;
            case 2 -> round2DistanceKm;
            case 3 -> round3DistanceKm;
            default -> round1DistanceKm;
        };
    }

    private double getOverlapForRound(int round) {
        return switch (round) {
            case 1 -> round1TimeOverlap;
            case 2 -> round2TimeOverlap;
            default -> round2TimeOverlap; // 第3轮也用60%
        };
    }

}

package com.example.demo.service;

import com.example.demo.entity.CallRecord;
import com.example.demo.entity.CallRole;
import com.example.demo.entity.CallStatus;
import com.example.demo.repository.CallRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 隐私号通话服务 —— 模拟实现
 * 当 aliyun.private-number.enabled=true 时替换为 AliyunPrivateNumberService
 *
 * 【模拟行为】
 * - 生成模拟虚拟号（170 开头）
 * - 返回 CONNECTED 状态，前端可联调通话 UI
 * - 日志标记【模拟通话】方便识别
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aliyun.private-number.enabled", havingValue = "false", matchIfMissing = true)
public class PrivateNumberService {

    private final CallRecordRepository callRecordRepository;

    public PrivateNumberService(CallRecordRepository callRecordRepository) {
        this.callRecordRepository = callRecordRepository;
    }

    /**
     * 发起通话（模拟版本）
     */
    public Map<String, Object> initiateCall(Long orderId, Long callerId, CallRole callerRole,
                                             Long calleeId, CallRole calleeRole) {
        // 生成模拟虚拟号
        String virtualNumber = generateMockVirtualNumber();

        CallRecord record = new CallRecord();
        record.setOrderId(orderId);
        record.setCallerId(callerId);
        record.setCallerRole(callerRole);
        record.setCalleeId(calleeId);
        record.setCalleeRole(calleeRole);
        record.setVirtualNumber(virtualNumber);
        record.setStatus(CallStatus.CONNECTED);
        record.setConnectedAt(LocalDateTime.now());

        callRecordRepository.save(record);

        log.info("【模拟通话】已接通, callRecordId={}, orderId={}, virtualNumber={}, {} → {}",
                record.getId(), orderId, virtualNumber, callerRole, calleeRole);

        return Map.of(
                "callRecordId", record.getId(),
                "status", "CONNECTED",
                "virtualNumber", virtualNumber,
                "message", "通话已接通（模拟）"
        );
    }

    /**
     * 获取订单的通话记录
     */
    public List<CallRecord> getCallRecords(Long orderId) {
        return callRecordRepository.findByOrderIdOrderByInitiatedAtDesc(orderId);
    }

    /**
     * 生成模拟虚拟号 —— 170 开头 + 8 位随机数
     */
    private String generateMockVirtualNumber() {
        long suffix = ThreadLocalRandom.current().nextLong(10000000, 99999999);
        return "170" + suffix;
    }
}

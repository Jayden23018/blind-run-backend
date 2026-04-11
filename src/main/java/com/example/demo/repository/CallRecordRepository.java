package com.example.demo.repository;

import com.example.demo.entity.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通话记录数据访问层
 */
@Repository
public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {

    /** 查询某订单的所有通话记录 */
    List<CallRecord> findByOrderIdOrderByInitiatedAtDesc(Long orderId);
}

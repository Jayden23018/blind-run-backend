package com.example.demo.repository;

import com.example.demo.entity.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 紧急联系人数据访问层
 */
@Repository
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Long> {

    /** 查询某用户的所有紧急联系人 */
    List<EmergencyContact> findByUserIdOrderByIsPrimaryDesc(Long userId);

    /** 查询某用户的主要联系人 */
    Optional<EmergencyContact> findByUserIdAndIsPrimaryTrue(Long userId);

    /** 统计某用户的紧急联系人数量 */
    long countByUserId(Long userId);

    /** 删除某用户的所有联系人（数据迁移用） */
    void deleteByUserId(Long userId);
}

package com.example.demo.repository;

import com.example.demo.entity.NotificationTemplate;
import com.example.demo.entity.TargetRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 通知模板数据访问层
 */
@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    /** 查询活跃的模板 */
    List<NotificationTemplate> findByIsActiveTrue();

    /** 按事件类型查询模板 */
    List<NotificationTemplate> findByEventTypeAndIsActiveTrue(String eventType);

    /** 按事件类型和目标角色查询模板 */
    Optional<NotificationTemplate> findByEventTypeAndTargetRoleAndIsActiveTrue(String eventType, TargetRole targetRole);
}

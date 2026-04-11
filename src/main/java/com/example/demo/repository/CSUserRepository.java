package com.example.demo.repository;

import com.example.demo.entity.CSUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 客服用户数据访问层
 */
@Repository
public interface CSUserRepository extends JpaRepository<CSUser, Long> {

    /** 按用户名查找 */
    Optional<CSUser> findByUsername(String username);

    /** 查询在线客服 */
    List<CSUser> findByIsOnlineTrue();
}

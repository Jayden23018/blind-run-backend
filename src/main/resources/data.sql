-- 默认客服账号：admin / admin123，角色 ADMIN
-- htpasswd 生成的 BCrypt hash（$2y$ → $2a$，Java BCryptPasswordEncoder 兼容）
-- 只在 username 不存在时插入，避免重复
INSERT INTO cs_users (username, password_hash, name, department, role, is_online, created_at)
SELECT 'admin', '$2a$10$ECZcgRYuB7HINVUqXQf2tO/VY9FHTnh7TDeMRABSQBoaRjDdMSfuC', '系统管理员', '运营部', 'ADMIN', false, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cs_users WHERE username = 'admin');

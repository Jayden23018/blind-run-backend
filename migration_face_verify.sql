-- 人脸认证功能数据库迁移
-- 执行方式：mysql -u root -p blind_running < migration_face_verify.sql
-- 或在生产服务器上执行：mysql -u root -p blind_running -e "source /path/to/migration_face_verify.sql"

-- 1. 人脸认证相关字段
ALTER TABLE volunteer_profile ADD COLUMN face_verify_certify_id VARCHAR(64) DEFAULT NULL;
ALTER TABLE volunteer_profile ADD COLUMN face_verify_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE volunteer_profile ADD COLUMN face_verify_rejection_reason VARCHAR(500) DEFAULT NULL;

-- 2. 志愿者可服务状态（FE-1功能，可能已存在，这里用 IF NOT EXISTS 风格）
-- 如果列已存在会报错，可以跳过这一条
-- ALTER TABLE volunteer_profile ADD COLUMN wants_dispatch BOOLEAN NOT NULL DEFAULT TRUE;

-- 验证迁移结果
SELECT
    COLUMN_NAME,
    DATA_TYPE,
    CHARACTER_MAXIMUM_LENGTH,
    IS_NULLABLE,
    COLUMN_DEFAULT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'blind_running'
  AND TABLE_NAME = 'volunteer_profile'
  AND COLUMN_NAME IN ('face_verify_certify_id', 'face_verify_status', 'face_verify_rejection_reason', 'wants_dispatch')
ORDER BY ORDINAL_POSITION;

-- 陪跑实时位置 + 轨迹回放功能（run_order_track_point 表 + ESCORT_DISTANCE_ALERT 通知模板）
-- 已于 2026-07-19 手动执行到生产库（ddl-auto=validate，Hibernate 不会自动建表）

CREATE TABLE run_order_track_point (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  latitude DOUBLE NOT NULL,
  longitude DOUBLE NOT NULL,
  recorded_at DATETIME NOT NULL,
  INDEX idx_order_role_time (order_id, role, recorded_at)
);

INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ESCORT_DISTANCE_ALERT', 'BLIND_USER', 'WEBSOCKET', '与志愿者的距离似乎有点远', '你和志愿者的距离似乎有点远，请留在原地，志愿者正在确认位置', 'HIGH', true);

INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ESCORT_DISTANCE_ALERT', 'VOLUNTEER', 'WEBSOCKET', '与盲人用户的距离似乎有点远', '你和盲人用户的距离似乎有点远，请尽快确认对方位置', 'HIGH', true);

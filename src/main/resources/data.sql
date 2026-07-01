-- 默认客服账号：admin / admin123，角色 ADMIN
-- htpasswd 生成的 BCrypt hash（$2y$ → $2a$，Java BCryptPasswordEncoder 兼容）
-- 只在 username 不存在时插入，避免重复
INSERT INTO cs_users (username, password_hash, name, department, role, is_online, created_at)
SELECT 'admin', '$2a$10$ECZcgRYuB7HINVUqXQf2tO/VY9FHTnh7TDeMRABSQBoaRjDdMSfuC', '系统管理员', '运营部', 'ADMIN', false, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cs_users WHERE username = 'admin');

-- ============================================================
-- 通知模板初始数据（INSERT IGNORE 避免重复插入）
-- ============================================================

-- 1. 订单被接单 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ORDER_ACCEPTED', 'BLIND_USER', 'WEBSOCKET', '已为您匹配志愿者{volunteerName}，他正在确认行程，请稍候', '已为您匹配志愿者{volunteerName}，他正在确认行程，请稍候', 'NORMAL', true);

-- 2. 重新匹配接单 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('REMATCH_ACCEPTED', 'BLIND_USER', 'WEBSOCKET', '已为您重新匹配志愿者{volunteerName}，服务即将开始', '已为您重新匹配志愿者{volunteerName}，服务即将开始', 'NORMAL', true);

-- 3. 志愿者出发 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('DRIVER_EN_ROUTE', 'BLIND_USER', 'WEBSOCKET', '志愿者{volunteerName}已出发，正在赶往您的位置', '志愿者{volunteerName}已出发，正在赶往您的位置，如需联系请点击通话按钮', 'NORMAL', true);

-- 4. 志愿者到达 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('DRIVER_ARRIVED', 'BLIND_USER', 'WEBSOCKET', '志愿者{volunteerName}已到达附近', '志愿者{volunteerName}已到达您附近，请注意周围，可点击通话按钮确认位置', 'HIGH', true);

-- 4.5. 志愿者确认开始服务 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('SERVICE_STARTED', 'BLIND_USER', 'WEBSOCKET', '服务已开始', '志愿者{volunteerName}已确认开始服务，陪跑正式开始', 'HIGH', true);

-- 5. 订单完成 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ORDER_COMPLETED', 'BLIND_USER', 'WEBSOCKET', '订单已完成', '本次跑步已完成，感谢您的使用', 'NORMAL', true);

-- 6. 志愿者取消（重新匹配）→ 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('REMATCHING', 'BLIND_USER', 'WEBSOCKET', '志愿者已取消，正在重新匹配', '您的志愿者已取消服务，系统正在为您重新匹配，请稍候', 'NORMAL', true);

-- 7. 重新匹配超时提醒 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('REMATCH_TIMEOUT', 'BLIND_USER', 'WEBSOCKET', '暂时没有可用志愿者，仍在等待', '暂时没有可用志愿者，您的订单仍在等待中，如需取消请手动操作', 'NORMAL', true);

-- 8. 匹配超时提醒 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('NO_VOLUNTEER_AVAILABLE', 'BLIND_USER', 'WEBSOCKET', '暂时没有可用志愿者，仍在等待', '暂时没有可用志愿者，您的订单仍在等待中，如需取消请手动操作', 'NORMAL', true);

-- 9. 订单超时挂起 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ORDER_OVERDUE', 'VOLUNTEER', 'WEBSOCKET', '订单已超过结束时间1小时', '您有一个订单已超过预定结束时间1小时，请确认是否需要结束订单', 'HIGH', true);

-- 10. 邻近感知 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('PROXIMITY_ALERT', 'BLIND_USER', 'WEBSOCKET', '志愿者距您约100米', '志愿者距您约100米，如需帮助请点击通话按钮联系他', 'NORMAL', true);

-- 11. 邻近感知 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('PROXIMITY_ALERT', 'VOLUNTEER', 'WEBSOCKET', '您已到达盲人附近', '您已到达盲人附近，如遇困难请点击通话按钮联系他', 'NORMAL', true);

-- 12. 紧急事件触发 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('EMERGENCY_TRIGGERED', 'BLIND_USER', 'WEBSOCKET', '已收到求助，正在通知志愿者', '紧急求助已发出，系统正在通知志愿者，请保持冷静，志愿者会尽快确认', 'HIGH', true);

-- 13. 紧急联系人已通知 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('EMERGENCY_CONTACT_NOTIFIED', 'BLIND_USER', 'WEBSOCKET', '已通知紧急联系人{contactName}', '已通知你的联系人{contactName}，请保持冷静', 'HIGH', true);

-- 紧急：用户未设置紧急联系人 → 通知盲人已转客服处理（S5，防止"正在通知家人"承诺落空）
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('EMERGENCY_NO_CONTACT', 'BLIND_USER', 'WEBSOCKET', '未找到紧急联系人，已转客服。情况危险请立即拨110', '情况危险请立即拨打110，或请周围人帮忙，客服也已收到求助', 'HIGH', true);

-- 14. 志愿者取消服务 → 通知盲人
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('VOLUNTEER_CANCELLED', 'BLIND_USER', 'WEBSOCKET', '志愿者已取消，正在重新匹配', '您的志愿者已取消服务，系统正在为您重新匹配，请稍候', 'NORMAL', true);

-- ============================================================
-- 培训课程初始数据
-- ============================================================

-- 1. 志愿服务基础规范
INSERT IGNORE INTO training_courses (id, title, description, duration_minutes, video_url, content, display_order, is_active)
VALUES (1, '志愿服务基础规范', '了解志愿服务的基本规范和注意事项', 15, NULL, '<h2>课程大纲</h2><p>1. 志愿者基本准则</p><p>2. 服务规范</p><p>3. 注意事项</p>', 1, true);

-- 2. 盲人陪跑服务指南
INSERT IGNORE INTO training_courses (id, title, description, duration_minutes, video_url, content, display_order, is_active)
VALUES (2, '盲人陪跑服务指南', '学习如何安全、专业地提供陪跑服务', 20, NULL, '<h2>服务流程</h2><p>1. 接单前准备</p><p>2. 陪跑过程</p><p>3. 服务结束</p>', 2, true);

-- 3. 紧急情况处理
INSERT IGNORE INTO training_courses (id, title, description, duration_minutes, video_url, content, display_order, is_active)
VALUES (3, '紧急情况处理', '掌握紧急情况的应对方法和求助流程', 10, NULL, '<h2>应急预案</h2><p>1. 紧急按钮使用</p><p>2. 求助流程</p><p>3. 后续处理</p>', 3, true);

-- ============================================================
-- 培训测验题目初始数据
-- ============================================================

-- 课程1：志愿服务基础规范（3道题）
INSERT IGNORE INTO training_quiz_questions (course_id, question_text, question_type, options, correct_answer, explanation, display_order)
VALUES
(1, '志愿者接单后应在多少分钟内联系盲人用户？', 'SINGLE_CHOICE', '["5分钟内","10分钟内","15分钟内","30分钟内"]', '["5分钟内"]', '及时沟通是服务质量的关键', 1),
(1, '以下哪项是志愿者的基本准则？', 'SINGLE_CHOICE', '["可以随意取消订单","保持友好和耐心","自行改变路线","接单后不联系用户"]', '["保持友好和耐心"]', '志愿者应始终保持专业态度', 2),
(1, '志愿者必须完成哪些步骤才能接单？（多选）', 'MULTIPLE_CHOICE', '["填写基本信息","上传身份证","人脸验证","完成培训"]', '["填写基本信息","上传身份证","人脸验证","完成培训"]', '所有步骤都是必须的', 3);

-- 课程2：盲人陪跑服务指南（3道题）
INSERT IGNORE INTO training_quiz_questions (course_id, question_text, question_type, options, correct_answer, explanation, display_order)
VALUES
(2, '陪跑过程中志愿者应走在盲人的哪个位置？', 'SINGLE_CHOICE', '["正前方","左前方半步","右后方","正后方"]', '["左前方半步"]', '标准陪跑位置', 1),
(2, '遇到台阶时应如何提示盲人？', 'SINGLE_CHOICE', '["直接拉过去","口头提示并引导台阶位置","不管","快速通过"]', '["口头提示并引导台阶位置"]', '清晰提示是安全的保障', 2),
(2, '陪跑服务的标准时长是多少？', 'SINGLE_CHOICE', '["30分钟","60分钟","90分钟","不限时长"]', '["60分钟"]', '标准服务时长', 3);

-- 课程3：紧急情况处理（3道题）
INSERT IGNORE INTO training_quiz_questions (course_id, question_text, question_type, options, correct_answer, explanation, display_order)
VALUES
(3, '盲人用户触发紧急求助后，志愿者应在多少秒内确认？', 'SINGLE_CHOICE', '["10秒内","30秒内","60秒内","不限时间"]', '["30秒内"]', '快速响应紧急情况', 1),
(3, '以下哪种情况需要通知紧急联系人？', 'SINGLE_CHOICE', '["盲人用户要求","志愿者判断需要帮助","误触紧急按钮","以上都是"]', '["志愿者判断需要帮助"]', '准确判断是关键', 2),
(3, '紧急事件解决后应如何处理？', 'SINGLE_CHOICE', '["直接离开","填写事件报告","通知管理员","以上都是"]', '["以上都是"]', '完整的后续处理流程', 3);

-- ============================================================
-- 新增通知模板（志愿者认证相关）
-- ============================================================

-- 15. 身份证审核通过 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ID_VERIFY_APPROVED', 'VOLUNTEER', 'WEBSOCKET', '您的身份证认证已通过', '您的身份证认证已通过，请继续下一步人脸验证', 'NORMAL', true);

-- 16. 身份证审核拒绝 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ID_VERIFY_REJECTED', 'VOLUNTEER', 'WEBSOCKET', '您的身份证认证未通过，原因：{reason}', '您的身份证认证未通过，原因：{reason}，请重新提交', 'HIGH', true);

-- 17. 培训完成 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('TRAINING_COMPLETED', 'VOLUNTEER', 'WEBSOCKET', '恭喜您完成所有必修课程，现在可以接单了', '恭喜您完成所有必修课程，现在可以接单了', 'HIGH', true);

-- 18. 资质证书审核通过 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('CERT_APPROVED', 'VOLUNTEER', 'WEBSOCKET', '您的资质证书审核已通过', '您的资质证书审核已通过，感谢您的配合', 'NORMAL', true);

-- 19. 资质证书审核拒绝 → 通知志愿者
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('CERT_REJECTED', 'VOLUNTEER', 'WEBSOCKET', '您的资质证书审核未通过，原因：{reason}', '您的资质证书审核未通过，原因：{reason}，请重新上传', 'HIGH', true);

-- 20. 派单扩圈 → 通知盲人（A8 修复：原代码发通知但无模板，静默失效）
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('DISPATCH_EXPANDING', 'BLIND_USER', 'WEBSOCKET', '正在为您扩大搜索范围（第{round}轮），请稍候', '正在为您寻找更远的志愿者，请稍候', 'NORMAL', true);

-- 20b. 派单启动 → 通知盲人（A8-② 首次正向反馈：订单已开始呼叫志愿者，参考滴滴"正在为您呼叫"）
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('DISPATCH_STARTED', 'BLIND_USER', 'WEBSOCKET', '已收到订单，正在为您呼叫志愿者，请稍候', '已收到订单，正在为您呼叫附近的志愿者，请稍候', 'NORMAL', true);

-- 21. 订单自动取消 → 通知盲人（A8 修复：原代码发通知但无模板，静默失效；订单被系统取消时盲人收不到）
INSERT IGNORE INTO notification_templates (event_type, target_role, channel, template_text, tts_text, priority, is_active)
VALUES ('ORDER_AUTO_CANCELLED', 'BLIND_USER', 'WEBSOCKET', '暂未匹配到志愿者，订单已取消，可稍后重新发起', '抱歉，暂未匹配到志愿者，订单已取消，您可以稍后重新发起', 'HIGH', true);

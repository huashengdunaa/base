-- ============================================================
-- RabbitMQ 模块基础数据（仅 RabbitMqApplication 加载）
-- 由 application-rabbitmq.properties 中的 spring.sql.init.data-locations 指定
-- 执行时机：Hibernate 建表之后（defer-datasource-initialization=true）
-- 说明：H2 内存库每次启动都是全新的，这里不需要做幂等处理
-- ============================================================

INSERT INTO user_dead_letter (msg_id, topic, exception_msg, raw_data, create_date) VALUES
('3f8a1c9d5b2e47f0a6c1d8e2b4f70931', 'points.queue',
 '积分服务调用超时: Read timed out after 3000ms',
 '{"userId":1,"username":"alice","email":"alice@demo.com"}',
 CURRENT_TIMESTAMP - INTERVAL '2' HOUR),

('7d2b4e6f8a1c3e5b9f0d2a4c6e8b1d37', 'points.queue',
 '重试 3 次仍失败: 积分账户不存在, userId=1024',
 '{"userId":1024,"username":"bob","email":"bob@demo.com"}',
 CURRENT_TIMESTAMP - INTERVAL '95' MINUTE),

('b5e9c3a7d1f04862b8e4a0c6d2f9e535', 'email.queue',
 '邮件服务返回 550: mailbox unavailable',
 '{"userId":2048,"username":"carol","email":"carol@not-exist.com"}',
 CURRENT_TIMESTAMP - INTERVAL '70' MINUTE),

('c1a7e5b3d9f24680a2c4e6b8d0f1a247', 'points.queue',
 'JSON 反序列化失败: Unrecognized field "user_name"',
 '{"user_id":3072,"user_name":"dave","email":"dave@demo.com"}',
 CURRENT_TIMESTAMP - INTERVAL '40' MINUTE),

('e4b8d2f6a0c35719b3d5f7a9c1e3b257', 'points.queue',
 '数据库连接池耗尽: HikariPool-1 - Connection is not available, request timed out after 30000ms',
 '{"userId":4096,"username":"erin","email":"erin@demo.com"}',
 CURRENT_TIMESTAMP - INTERVAL '15' MINUTE),

('9a6c2e8b4d0f1735a9c3e5b7d1f4a267', 'points.queue',
 '人工重放后再次失败: 积分规则配置缺失, ruleId=points.register.gift',
 '{"userId":5120,"username":"frank","email":"frank@demo.com"}',
 CURRENT_TIMESTAMP - INTERVAL '3' MINUTE);

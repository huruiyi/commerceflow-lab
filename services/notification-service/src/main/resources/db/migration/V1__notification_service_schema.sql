CREATE TABLE customer_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '独立通知主键',
  event_id VARCHAR(100) NOT NULL COMMENT '上游命令事件标识',
  customer_id BIGINT NOT NULL COMMENT '身份服务提供的客户标识快照',
  order_id BIGINT NULL COMMENT '订单标识快照，不建立跨 Schema 外键',
  notification_type VARCHAR(50) NOT NULL COMMENT '通知业务类型',
  title VARCHAR(160) NOT NULL COMMENT '通知标题',
  message VARCHAR(500) NOT NULL COMMENT '通知正文',
  status VARCHAR(20) NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/READ',
  read_at TIMESTAMP(6) NULL COMMENT '客户首次读时间',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '通知创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_event UNIQUE (event_id),
  KEY ix_notification_customer_created (customer_id, created_at)
) COMMENT='独立 notification Schema 客户通知读模型';

CREATE TABLE notification_inbox (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '独立消费者 Inbox 主键',
  consumer_name VARCHAR(100) NOT NULL COMMENT '稳定消费者名称',
  event_id VARCHAR(100) NOT NULL COMMENT '全局幂等事件标识',
  event_type VARCHAR(80) NOT NULL COMMENT '版本化命令类型',
  schema_version INT NOT NULL COMMENT '命令结构版本',
  correlation_id VARCHAR(100) NOT NULL COMMENT '跨进程业务关联标识',
  causation_id VARCHAR(100) NOT NULL COMMENT '直接因果事件标识',
  traceparent VARCHAR(55) NOT NULL COMMENT 'W3C Trace Context 传播值',
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次成功消费时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_inbox_consumer_event UNIQUE (consumer_name, event_id)
) COMMENT='独立 notification Schema 消费幂等 Inbox';

CREATE TABLE notification_delivery_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知投递 Outbox 主键',
  event_id VARCHAR(100) NOT NULL COMMENT '全局事件标识',
  topic VARCHAR(100) NOT NULL COMMENT '异步传输目标 Topic',
  payload JSON NOT NULL COMMENT '完整版本化通知命令',
  status VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/RETRY/PUBLISHED/FALLBACK/DEAD',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已完成投递尝试次数',
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许投递时间',
  last_error VARCHAR(500) NULL COMMENT '最近一次失败原因',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '进入 Outbox 时间',
  published_at TIMESTAMP(6) NULL COMMENT 'Broker 确认时间',
  processed_at TIMESTAMP(6) NULL COMMENT 'FALLBACK/DEAD 终态时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_delivery_event UNIQUE (event_id),
  KEY ix_notification_delivery_dispatch (status, next_attempt_at)
) COMMENT='独立 notification Schema 可靠投递 Outbox';

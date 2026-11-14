ALTER TABLE notification_inbox
  ADD COLUMN correlation_id VARCHAR(100) NULL COMMENT '跨进程业务关联标识',
  ADD COLUMN causation_id VARCHAR(100) NULL COMMENT '触发当前命令的直接事件标识',
  ADD COLUMN traceparent VARCHAR(55) NULL COMMENT 'W3C Trace Context 传播值';

CREATE TABLE notification_delivery_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知投递 Outbox 主键',
  event_id VARCHAR(100) NOT NULL COMMENT '版本化通知命令的全局事件标识',
  topic VARCHAR(100) NOT NULL COMMENT '异步传输目标 Topic',
  payload JSON NOT NULL COMMENT '完整版本化通知命令',
  status VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/RETRY/PUBLISHED/FALLBACK/DEAD',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已经完成的投递尝试次数',
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次允许投递时间',
  last_error VARCHAR(500) NULL COMMENT '最近一次投递或回退失败原因',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '命令进入 Outbox 的时间',
  published_at TIMESTAMP(6) NULL COMMENT 'Kafka 确认接收时间',
  processed_at TIMESTAMP(6) NULL COMMENT '进入 FALLBACK 或 DEAD 的时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_delivery_event UNIQUE (event_id),
  KEY ix_notification_delivery_dispatch (status, next_attempt_at)
) COMMENT='通知模块异步传输 Outbox 与本地死信队列';

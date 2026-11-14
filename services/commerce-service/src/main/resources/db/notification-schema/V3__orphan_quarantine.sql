CREATE TABLE notification_storage_quarantine (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '隔离证据主键',
  original_notification_id BIGINT NOT NULL COMMENT '独立读模型原通知主键',
  event_id VARCHAR(100) NOT NULL COMMENT '被隔离的上游事件标识',
  customer_id BIGINT NOT NULL COMMENT '客户标识快照',
  order_id BIGINT NULL COMMENT '订单标识快照',
  notification_type VARCHAR(50) NOT NULL COMMENT '通知业务类型',
  title VARCHAR(160) NOT NULL COMMENT '通知标题快照',
  message VARCHAR(500) NOT NULL COMMENT '通知正文快照',
  status VARCHAR(20) NOT NULL COMMENT '隔离前已读状态',
  read_at TIMESTAMP(6) NULL COMMENT '隔离前首次读取时间',
  created_at TIMESTAMP(6) NOT NULL COMMENT '独立读模型创建时间',
  reason VARCHAR(100) NOT NULL COMMENT '隔离原因',
  quarantined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '隔离时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_quarantine_event UNIQUE (event_id)
) COMMENT='Kafka 历史重放产生的独立 Schema 孤儿通知隔离证据';

CREATE TABLE notification_inbox (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知消费者收件箱主键',
  consumer_name VARCHAR(100) NOT NULL COMMENT '处理该命令的稳定消费者名称',
  event_id VARCHAR(100) NOT NULL COMMENT '生产方提供的全局幂等事件标识',
  event_type VARCHAR(80) NOT NULL COMMENT '版本化通知命令类型',
  schema_version INT NOT NULL COMMENT '通知命令结构版本',
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次成功领取命令的时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_notification_inbox_consumer_event UNIQUE (consumer_name, event_id)
) COMMENT='通知模块独立 Inbox，用于命令消费幂等';

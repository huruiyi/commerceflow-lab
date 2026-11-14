CREATE TABLE notification_projection_windows (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投影核对窗口主键',
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/READY/STOPPED',
  start_notification_id BIGINT NOT NULL COMMENT '窗口启动时已有通知的最大主键',
  started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '核对窗口开始时间',
  ready_at TIMESTAMP(6) NULL COMMENT '首次满足连续停止条件的时间',
  stopped_at TIMESTAMP(6) NULL COMMENT '管理员确认停止窗口的时间',
  PRIMARY KEY (id),
  KEY ix_notification_projection_window_status (status, started_at)
) COMMENT='notification 影子投影核对窗口及停止状态';

CREATE TABLE notification_projection_shadow (
  window_id BIGINT NOT NULL COMMENT '所属投影核对窗口',
  event_id VARCHAR(100) NOT NULL COMMENT '影子投影使用的通知事件标识',
  customer_id BIGINT NOT NULL COMMENT '目标客户标识快照',
  order_id BIGINT NULL COMMENT '关联订单标识快照',
  notification_type VARCHAR(50) NOT NULL COMMENT '通知业务类型',
  title VARCHAR(160) NOT NULL COMMENT '通知标题快照',
  message VARCHAR(500) NOT NULL COMMENT '通知正文快照',
  correlation_id VARCHAR(100) NOT NULL COMMENT '跨进程业务关联标识',
  causation_id VARCHAR(100) NOT NULL COMMENT '直接因果事件标识',
  traceparent VARCHAR(55) NOT NULL COMMENT 'W3C Trace Context 传播值',
  projected_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '影子消费者首次投影时间',
  PRIMARY KEY (window_id, event_id),
  CONSTRAINT fk_notification_projection_window FOREIGN KEY (window_id)
    REFERENCES notification_projection_windows(id),
  KEY ix_notification_projection_customer (customer_id, projected_at)
) COMMENT='notification 拆分前异步影子投影，不作为业务读模型';

CREATE TABLE notification_projection_audits (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投影核对批次主键',
  window_id BIGINT NOT NULL COMMENT '所属投影核对窗口',
  matched_count INT NOT NULL COMMENT '主表与影子投影完全一致的事件数',
  missing_shadow_count INT NOT NULL COMMENT '主表存在但影子投影缺失的事件数',
  orphan_shadow_count INT NOT NULL COMMENT '影子投影存在但主表缺失的事件数',
  mismatch_count INT NOT NULL COMMENT '同事件业务字段不一致的数量',
  blocking_outbox_count INT NOT NULL COMMENT 'NEW/RETRY/DEAD/FALLBACK 阻塞记录数',
  passed BOOLEAN NOT NULL COMMENT '本次是否满足单轮停止条件',
  checked_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '核对完成时间',
  PRIMARY KEY (id),
  CONSTRAINT fk_notification_projection_audit_window FOREIGN KEY (window_id)
    REFERENCES notification_projection_windows(id),
  KEY ix_notification_projection_audit_time (checked_at)
) COMMENT='notification 影子投影核对窗口证据';

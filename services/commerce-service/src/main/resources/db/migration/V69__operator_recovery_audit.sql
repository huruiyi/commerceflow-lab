ALTER TABLE inventory_stocktake_reviews
  ADD COLUMN approval_reason VARCHAR(300) NULL COMMENT '独立审批人的复核依据和处置理由';

ALTER TABLE merchant_payout_attempts
  ADD COLUMN recovery_of CHAR(36) NULL COMMENT '本次恢复对应的原失败打款尝试',
  ADD COLUMN recovery_reason VARCHAR(300) NULL COMMENT '管理员核对原渠道回执后的恢复理由',
  ADD CONSTRAINT fk_payout_recovery_source FOREIGN KEY (recovery_of) REFERENCES merchant_payout_attempts(id),
  ADD UNIQUE KEY uk_payout_recovery_source (recovery_of);

ALTER TABLE invoice_review_actions
  ADD COLUMN request_id CHAR(36) NULL COMMENT '复核操作在该订单内的幂等请求号',
  ADD UNIQUE KEY uk_invoice_review_request (order_id, request_id);

CREATE TABLE notification_delivery_recoveries (
  event_id VARCHAR(100) NOT NULL COMMENT '保留原通知事件号',
  request_id CHAR(36) NOT NULL COMMENT '该事件的人工重试请求号',
  actor_id BIGINT NOT NULL COMMENT '有权恢复通知的管理员',
  reason VARCHAR(300) NOT NULL COMMENT '人工复核并恢复原事件的理由',
  prior_attempts INT NOT NULL COMMENT '人工恢复前已尝试次数',
  prior_error VARCHAR(500) NULL COMMENT '本次处置前的最终失败信息',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id, request_id),
  KEY idx_delivery_recovery_time (event_id, created_at)
);

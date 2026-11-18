CREATE TABLE fulfillment_merchant_recoveries (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '恢复审计记录标识',
  command_id CHAR(36) NOT NULL COMMENT '原商家履约命令标识',
  recovery_id CHAR(36) NOT NULL COMMENT '管理员提供的恢复请求标识',
  operator_id BIGINT NOT NULL COMMENT '管理员会话对应的用户标识',
  step VARCHAR(24) NOT NULL COMMENT '重新入队时的原步骤',
  previous_error VARCHAR(80) NULL COMMENT '重新入队前的错误码',
  reason VARCHAR(300) NOT NULL COMMENT '管理员提供的恢复原因',
  evidence VARCHAR(500) NOT NULL COMMENT '管理员核对下游后提供的证据',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '恢复请求受理时间',
  UNIQUE KEY uk_fulfillment_merchant_recovery (command_id,recovery_id),
  INDEX idx_fulfillment_merchant_recovery_history (command_id,id),
  CONSTRAINT fk_fulfillment_merchant_recovery_job FOREIGN KEY (command_id)
    REFERENCES fulfillment_merchant_jobs (command_id)
);

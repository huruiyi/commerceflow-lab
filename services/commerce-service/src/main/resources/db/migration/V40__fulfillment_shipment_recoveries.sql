CREATE TABLE fulfillment_shipment_recoveries (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '恢复审计记录标识',
  order_id BIGINT NOT NULL COMMENT '履约操作对应的订单标识',
  recovery_id CHAR(36) NOT NULL COMMENT '管理端提供的稳定恢复请求标识',
  command_id CHAR(36) NOT NULL COMMENT '原发货操作的命令标识',
  operator_id BIGINT NOT NULL COMMENT '从管理员会话解析的用户标识',
  step VARCHAR(32) NOT NULL COMMENT '管理员恢复时待执行的原步骤',
  previous_error VARCHAR(300) NULL COMMENT '本次恢复前记录的错误码',
  reason VARCHAR(300) NOT NULL COMMENT '管理员说明的恢复原因',
  evidence VARCHAR(500) NOT NULL COMMENT '管理员核对下游后提供的证据',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '恢复重新入队的时间',
  UNIQUE KEY uk_fulfillment_shipment_recovery (order_id,recovery_id),
  INDEX idx_fulfillment_shipment_recovery_history (order_id,id)
);

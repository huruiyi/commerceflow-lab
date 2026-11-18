CREATE TABLE fulfillment_shipment_jobs (
  order_id BIGINT NOT NULL PRIMARY KEY COMMENT '订单服务标识，每单一个初始发货流程',
  command_id CHAR(36) NOT NULL UNIQUE COMMENT '本次发货的稳定操作标识',
  merchant_id BIGINT NOT NULL COMMENT '通过归属校验的商家标识',
  order_number VARCHAR(64) NOT NULL COMMENT '发送给物流渠道的订单号快照',
  carrier VARCHAR(100) NOT NULL COMMENT '受理时确认的承运商',
  tracking_number VARCHAR(120) NOT NULL COMMENT '受理时确认的运单号',
  step VARCHAR(32) NOT NULL COMMENT '下一次应执行的发货步骤',
  status VARCHAR(20) NOT NULL COMMENT '待执行、完成、拒绝或待核对',
  attempts INT NOT NULL DEFAULT 0 COMMENT '当前步骤的认领次数',
  owner_token CHAR(36) NULL COMMENT '当前租约所有者',
  lease_until TIMESTAMP(6) NULL COMMENT '租约回收时间',
  next_attempt_at TIMESTAMP(6) NOT NULL COMMENT '下一次后台认领时间',
  last_error VARCHAR(300) NULL COMMENT '最后一次失败的步骤错误码',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_fulfillment_shipment_due (status, next_attempt_at, order_id)
);

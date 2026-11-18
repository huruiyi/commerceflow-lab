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

CREATE TABLE fulfillment_merchant_jobs (
  command_id CHAR(36) NOT NULL PRIMARY KEY COMMENT '商家操作及其订单命令的稳定标识',
  order_id BIGINT NOT NULL COMMENT '外部订单标识',
  merchant_id BIGINT NOT NULL COMMENT '提交操作的商家',
  operation VARCHAR(24) NOT NULL COMMENT '确认接单或修改运单',
  expected_version BIGINT NOT NULL COMMENT '原运单版本，接单使用 0',
  carrier VARCHAR(100) NULL COMMENT '修改后的承运商',
  tracking_number VARCHAR(120) NULL COMMENT '修改后的运单号',
  detail VARCHAR(500) NULL COMMENT '订单事件记录的修改前后详情',
  step VARCHAR(24) NOT NULL COMMENT '下一次执行渠道或订单步骤',
  status VARCHAR(20) NOT NULL COMMENT '待执行、完成、拒绝或待核对',
  attempts INT NOT NULL DEFAULT 0 COMMENT '当前步骤认领次数',
  owner_token CHAR(36) NULL COMMENT '当前租约所有者',
  lease_until TIMESTAMP(6) NULL COMMENT '租约到期时间',
  next_attempt_at TIMESTAMP(6) NOT NULL COMMENT '下次后台执行时间',
  last_error VARCHAR(80) NULL COMMENT '最近一次失败的错误码',
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_fulfillment_merchant_operation (order_id,operation,expected_version),
  INDEX idx_fulfillment_merchant_due (status,next_attempt_at,command_id)
);

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

CREATE TABLE fulfillment_order_locks (
  order_id BIGINT NOT NULL PRIMARY KEY COMMENT '外部订单标识，同一订单的履约准入在此行串行化'
);

CREATE TABLE fulfillment_command_ids (
  command_id CHAR(36) NOT NULL PRIMARY KEY COMMENT '接单、发货、改运单共用的稳定命令标识',
  order_id BIGINT NOT NULL COMMENT '命令创建时绑定的外部订单标识'
);

INSERT INTO fulfillment_command_ids (command_id, order_id)
SELECT command_id, order_id FROM fulfillment_shipment_jobs
UNION ALL
SELECT command_id, order_id FROM fulfillment_merchant_jobs;

ALTER TABLE fulfillment_shipment_jobs
  DROP PRIMARY KEY,
  DROP INDEX command_id,
  ADD PRIMARY KEY (command_id),
  ADD INDEX idx_fulfillment_shipment_order (order_id, created_at, command_id);

ALTER TABLE fulfillment_merchant_jobs
  DROP INDEX uk_fulfillment_merchant_operation,
  ADD INDEX idx_fulfillment_merchant_operation (order_id, operation, expected_version);

CREATE VIEW fulfillment_shadow_rows AS
SELECT 'fulfillment_order_locks' AS table_name, CAST(order_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'order_id', order_id
  ) AS payload
FROM fulfillment_order_locks
UNION ALL
SELECT 'fulfillment_command_ids' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'command_id', command_id,
    'order_id', order_id
  ) AS payload
FROM fulfillment_command_ids
UNION ALL
SELECT 'fulfillment_shipment_jobs' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'order_id', order_id,
    'command_id', command_id,
    'merchant_id', merchant_id,
    'order_number', order_number,
    'carrier', carrier,
    'tracking_number', tracking_number,
    'step', step,
    'status', status,
    'attempts', attempts,
    'owner_token', owner_token,
    'lease_until', lease_until,
    'next_attempt_at', next_attempt_at,
    'last_error', last_error,
    'created_at', created_at,
    'updated_at', updated_at
  ) AS payload
FROM fulfillment_shipment_jobs
UNION ALL
SELECT 'fulfillment_merchant_jobs' AS table_name, CAST(command_id AS CHAR) AS row_key,
  JSON_OBJECT(
    'command_id', command_id,
    'order_id', order_id,
    'merchant_id', merchant_id,
    'operation', operation,
    'expected_version', expected_version,
    'carrier', carrier,
    'tracking_number', tracking_number,
    'detail', detail,
    'step', step,
    'status', status,
    'attempts', attempts,
    'owner_token', owner_token,
    'lease_until', lease_until,
    'next_attempt_at', next_attempt_at,
    'last_error', last_error,
    'updated_at', updated_at
  ) AS payload
FROM fulfillment_merchant_jobs
UNION ALL
SELECT 'fulfillment_shipment_recoveries' AS table_name, CAST(id AS CHAR) AS row_key,
  JSON_OBJECT(
    'id', id,
    'order_id', order_id,
    'recovery_id', recovery_id,
    'command_id', command_id,
    'operator_id', operator_id,
    'step', step,
    'previous_error', previous_error,
    'reason', reason,
    'evidence', evidence,
    'created_at', created_at
  ) AS payload
FROM fulfillment_shipment_recoveries
UNION ALL
SELECT 'fulfillment_merchant_recoveries' AS table_name, CAST(id AS CHAR) AS row_key,
  JSON_OBJECT(
    'id', id,
    'command_id', command_id,
    'recovery_id', recovery_id,
    'operator_id', operator_id,
    'step', step,
    'previous_error', previous_error,
    'reason', reason,
    'evidence', evidence,
    'created_at', created_at
  ) AS payload
FROM fulfillment_merchant_recoveries;

CREATE TABLE fulfillment_shadow_watermark (
  singleton_id TINYINT PRIMARY KEY,
  generation BIGINT NOT NULL,
  source_sequence BIGINT NOT NULL
);
INSERT INTO fulfillment_shadow_watermark VALUES (1, 0, 0);

CREATE TABLE fulfillment_shadow_quarantine (
  table_name VARCHAR(64) NOT NULL,
  row_key VARCHAR(64) NOT NULL,
  source_sequence BIGINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  PRIMARY KEY (table_name, row_key)
);

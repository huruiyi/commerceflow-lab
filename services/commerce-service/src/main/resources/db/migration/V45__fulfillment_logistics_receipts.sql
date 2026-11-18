CREATE TABLE logistics_fulfillment_commands (
  command_id CHAR(36) NOT NULL PRIMARY KEY COMMENT '履约步骤派生的稳定物流命令标识',
  operation VARCHAR(16) NOT NULL COMMENT '绑定初始运单或修改运单详情',
  order_id BIGINT NOT NULL COMMENT '外部订单标识',
  order_number VARCHAR(64) NULL COMMENT '初始建单使用的原订单号',
  carrier VARCHAR(100) NOT NULL COMMENT '受理时的承运商',
  tracking_number VARCHAR(120) NOT NULL COMMENT '受理时的运单号',
  expected_version BIGINT NULL COMMENT '修改运单的前置版本',
  platform_shipment_no VARCHAR(80) NULL COMMENT '成功时记录的原渠道运单标识',
  shipment_version BIGINT NULL COMMENT '成功时记录的原运单版本',
  owner_token CHAR(36) NULL COMMENT '当前执行者的租约标识',
  lease_until TIMESTAMP(6) NULL COMMENT '租约到期时间'
);

ALTER TABLE fulfillment_shipment_jobs
  DROP PRIMARY KEY,
  DROP INDEX command_id,
  ADD PRIMARY KEY (command_id),
  ADD INDEX idx_fulfillment_shipment_order (order_id, created_at, command_id);

ALTER TABLE fulfillment_merchant_jobs
  DROP INDEX uk_fulfillment_merchant_operation,
  ADD INDEX idx_fulfillment_merchant_operation (order_id, operation, expected_version);

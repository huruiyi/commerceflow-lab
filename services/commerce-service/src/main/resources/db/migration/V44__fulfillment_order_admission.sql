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

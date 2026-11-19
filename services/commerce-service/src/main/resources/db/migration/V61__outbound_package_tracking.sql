ALTER TABLE logistics_fulfillment_commands
  ADD COLUMN package_id CHAR(36) NULL COMMENT '包裹发货命令标识';

ALTER TABLE shipment_records
  DROP INDEX uk_storefront_shipment_order,
  DROP COLUMN outbound_order_id,
  ADD COLUMN package_id CHAR(36) NULL COMMENT '多包裹正向发货标识',
  ADD COLUMN package_delivery_checked_at TIMESTAMP(6) NULL COMMENT '最近一次核对整单送达的时间',
  ADD COLUMN package_delivery_reported BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该单已向订单服务报告所有包裹送达',
  ADD COLUMN outbound_order_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN after_sale_id IS NULL AND package_id IS NULL THEN order_id END
  ) STORED,
  ADD UNIQUE KEY uk_storefront_shipment_order (outbound_order_id),
  ADD UNIQUE KEY uk_storefront_shipment_package (package_id),
  ADD KEY idx_storefront_order_package (order_id,package_id);

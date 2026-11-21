CREATE TABLE line_after_sale_order_locks (
  order_id BIGINT NOT NULL COMMENT '串行化同一订单的退款和换货申请',
  PRIMARY KEY (order_id)
);

ALTER TABLE shipment_records
  ADD COLUMN replacement BOOLEAN NOT NULL DEFAULT FALSE COMMENT '售后关联运单是否用于补发换货件',
  ADD UNIQUE KEY uk_storefront_after_sale_parcel(after_sale_id,replacement);

ALTER TABLE shipment_records DROP INDEX uk_storefront_shipment_return;

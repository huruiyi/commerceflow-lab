ALTER TABLE refund_records
  DROP INDEX uk_refund_payment,
  ADD COLUMN after_sale_id CHAR(36) NULL COMMENT '商城订单行售后标识',
  ADD UNIQUE KEY uk_refund_after_sale (after_sale_id);

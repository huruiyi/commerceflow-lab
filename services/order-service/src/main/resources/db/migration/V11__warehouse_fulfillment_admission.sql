ALTER TABLE order_package_allocations
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 COMMENT '包裹分配的原仓编号';

ALTER TABLE order_line_refund_admissions
  ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1 COMMENT '未发退款释放的原仓编号';

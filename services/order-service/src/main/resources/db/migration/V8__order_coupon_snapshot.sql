ALTER TABLE shop_orders
  ADD COLUMN coupon_id CHAR(36) NULL COMMENT '成交时冻结的顾客券实例',
  ADD KEY idx_order_coupon_id (coupon_id),
  ADD CHECK (coupon_id IS NULL OR discount_amount > 0);

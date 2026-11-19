CREATE TABLE inventory_line_credits (
  case_id CHAR(36) PRIMARY KEY COMMENT '关联商城售后单，用于重试去重',
  order_id BIGINT NOT NULL COMMENT '原订单标识',
  order_line_id BIGINT NOT NULL COMMENT '原订单行标识',
  merchant_id BIGINT NOT NULL COMMENT '原订单行商家',
  product_id BIGINT NOT NULL COMMENT '原商品快照标识',
  quantity INT NOT NULL COMMENT '恢复到可售库存的件数',
  kind VARCHAR(24) NOT NULL COMMENT 'BEFORE_SHIPMENT 或 RETURN',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_inventory_credit_order (order_id,kind,product_id,order_line_id),
  CHECK (quantity > 0 AND kind IN ('BEFORE_SHIPMENT','RETURN'))
);

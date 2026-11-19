CREATE TABLE order_line_refund_admissions (
  case_id CHAR(36) PRIMARY KEY COMMENT '售后单的稳定标识',
  order_id BIGINT NOT NULL COMMENT '需要核对发货意图的原订单',
  order_line_id BIGINT NOT NULL COMMENT '原订单行标识',
  quantity INT NOT NULL COMMENT '锁定或已退款的件数',
  status VARCHAR(12) NOT NULL COMMENT 'HELD、SETTLED 或 RELEASED',
  KEY idx_line_refund_order_status (order_id,status,order_line_id),
  CONSTRAINT fk_line_refund_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
  CONSTRAINT fk_line_refund_item FOREIGN KEY (order_line_id) REFERENCES order_items(id),
  CHECK (quantity > 0 AND status IN ('HELD','SETTLED','RELEASED'))
);

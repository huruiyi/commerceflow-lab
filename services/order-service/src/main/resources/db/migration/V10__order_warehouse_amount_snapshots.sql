CREATE TABLE order_warehouse_amounts (
  order_id BIGINT NOT NULL COMMENT '该分配所属订单',
  order_line_id BIGINT NOT NULL COMMENT '原订单行编号',
  warehouse_id BIGINT NOT NULL COMMENT '库存服务确认的分配仓',
  quantity INT NOT NULL COMMENT '分配到该仓的订单行件数',
  gross_amount DECIMAL(12,2) NOT NULL COMMENT '未扣优惠的商品金额',
  discount_amount DECIMAL(12,2) NOT NULL COMMENT '继承订单行的优惠份额',
  freight_amount DECIMAL(12,2) NOT NULL COMMENT '用于对账的原运费份额',
  PRIMARY KEY (order_id,order_line_id,warehouse_id),
  CONSTRAINT fk_order_warehouse_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
  CONSTRAINT fk_order_warehouse_item FOREIGN KEY (order_line_id) REFERENCES order_items(id),
  CHECK (quantity > 0 AND gross_amount >= discount_amount AND discount_amount >= 0 AND freight_amount >= 0)
);

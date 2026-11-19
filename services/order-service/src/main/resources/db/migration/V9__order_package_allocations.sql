CREATE TABLE order_package_allocations (
  package_id CHAR(36) PRIMARY KEY COMMENT '履约包裹及出库请求的稳定标识',
  order_id BIGINT NOT NULL COMMENT '原订单标识',
  merchant_id BIGINT NOT NULL COMMENT '下单时的商家归属',
  carrier VARCHAR(100) NOT NULL COMMENT '发货承运商',
  tracking_number VARCHAR(120) NOT NULL COMMENT '包裹运单号',
  posted_at TIMESTAMP(6) NULL COMMENT '履约已确认库存出库和运单的时间',
  KEY idx_order_package_order (order_id),
  CONSTRAINT fk_order_package_order FOREIGN KEY (order_id) REFERENCES shop_orders(id)
);

CREATE TABLE order_package_lines (
  package_id CHAR(36) NOT NULL COMMENT '所属履约包裹标识',
  order_line_id BIGINT NOT NULL COMMENT '原订单行标识',
  quantity INT NOT NULL COMMENT '该包裹分配的发货件数',
  PRIMARY KEY (package_id,order_line_id),
  KEY idx_order_package_line (order_line_id,package_id),
  CONSTRAINT fk_order_package_line FOREIGN KEY (package_id) REFERENCES order_package_allocations(package_id),
  CONSTRAINT fk_order_package_item FOREIGN KEY (order_line_id) REFERENCES order_items(id),
  CHECK (quantity > 0)
);

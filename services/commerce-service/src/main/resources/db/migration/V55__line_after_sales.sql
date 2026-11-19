CREATE TABLE line_after_sales (
  id CHAR(36) NOT NULL COMMENT '稳定的售后单及跨服务操作标识',
  order_id BIGINT NOT NULL COMMENT '订单服务中的原订单标识',
  order_line_id BIGINT NOT NULL COMMENT '订单服务中冻结的订单行标识',
  product_id BIGINT NOT NULL COMMENT '成交商品标识',
  customer_id BIGINT NOT NULL COMMENT '原订单顾客',
  merchant_id BIGINT NOT NULL COMMENT '下单快照中的商家',
  quantity INT NOT NULL COMMENT '本次申请的商品件数',
  merchandise_amount DECIMAL(12,2) NOT NULL COMMENT '本次商品可退金额，含成交优惠分摊',
  shipping_amount DECIMAL(12,2) NOT NULL COMMENT '本次原发货运费可退金额',
  currency CHAR(3) NOT NULL COMMENT '订单成交币种',
  kind VARCHAR(24) NOT NULL COMMENT 'BEFORE_SHIPMENT 或 RETURN',
  status VARCHAR(24) NOT NULL COMMENT '售后处理阶段，包括待审核、待退货、执行中和终态',
  reason VARCHAR(300) NOT NULL COMMENT '顾客的售后原因',
  decision VARCHAR(300) NULL COMMENT '商家审核或验收说明',
  tracking_number VARCHAR(120) NULL COMMENT '顾客填写的退回运单号',
  refund_number VARCHAR(40) NULL COMMENT '支付编排生成的退款编号',
  return_shipping_cost DECIMAL(12,2) NULL COMMENT '顾客登记的退回运费，不计入退款',
  received_quantity INT NULL COMMENT '商家验收实收件数',
  last_error VARCHAR(300) NULL COMMENT '最近一次未完成操作的原因',
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_order_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status NOT IN ('COMPLETED','REJECTED','FAILED') THEN order_id END
  ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_line_after_sales_active_order (active_order_id),
  KEY idx_line_after_sales_customer (customer_id, order_id),
  KEY idx_line_after_sales_merchant (merchant_id, order_id),
  KEY idx_line_after_sales_work (status, next_attempt_at),
  KEY idx_line_after_sales_order (order_id),
  CONSTRAINT ck_line_after_sales_amounts CHECK (
    quantity > 0 AND merchandise_amount >= 0 AND shipping_amount >= 0
  )
);

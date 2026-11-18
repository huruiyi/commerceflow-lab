ALTER TABLE shop_orders
  ADD COLUMN shipment_intent_key CHAR(64) NULL COMMENT '发货商家与运单参数的稳定指纹';

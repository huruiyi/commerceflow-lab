ALTER TABLE shop_orders
  ADD COLUMN shipment_version BIGINT NOT NULL DEFAULT 0 COMMENT '订单侧运单详情的最新已确认版本';

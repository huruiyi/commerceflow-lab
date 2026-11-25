ALTER TABLE merchant_shipping_rates
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '区域运费是否参与结算匹配';

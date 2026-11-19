CREATE TABLE merchant_shipping_rates (
  merchant_id BIGINT NOT NULL COMMENT '拥有该规则的商城商家',
  country_code VARCHAR(2) NOT NULL COMMENT '目标国家，默认规则使用空串',
  state_province VARCHAR(100) NOT NULL COMMENT '目标省州，默认规则使用空串',
  fee DECIMAL(12,2) NOT NULL COMMENT '每单运费，人民币元',
  PRIMARY KEY (merchant_id, country_code, state_province),
  CONSTRAINT ck_merchant_shipping_rate CHECK (
    fee >= 0 AND ((country_code = '' AND state_province = '')
      OR (CHAR_LENGTH(country_code) = 2 AND state_province <> ''))
  )
);

CREATE TABLE checkout_receipts (
  customer_id BIGINT NOT NULL COMMENT '确认结算的顾客',
  quote_id CHAR(64) NOT NULL COMMENT '已确认报价的 SHA-256',
  scope_hash CHAR(64) NOT NULL COMMENT '下单请求所选地址和条目的摘要',
  order_id BIGINT NOT NULL COMMENT '订单服务生成的订单 ID',
  PRIMARY KEY (customer_id, quote_id),
  UNIQUE KEY uk_checkout_receipts_order (order_id)
);

CREATE TABLE coupon_campaigns (
  id CHAR(36) PRIMARY KEY COMMENT '活动版本标识，计价规则发布后冻结',
  merchant_id BIGINT NOT NULL COMMENT '活动归属商家',
  title VARCHAR(100) NOT NULL COMMENT '顾客可见活动名称',
  minimum_amount DECIMAL(12,2) NOT NULL COMMENT '适用商品金额门槛，人民币元',
  discount_amount DECIMAL(12,2) NOT NULL COMMENT '满减金额，人民币元',
  supply INT NOT NULL COMMENT '活动原始发放量',
  issued INT NOT NULL DEFAULT 0 COMMENT '已领取总数，取消返还不会重新发放',
  starts_at TIMESTAMP(6) NOT NULL COMMENT '开始领取和用券的时刻',
  expires_at TIMESTAMP(6) NOT NULL COMMENT '领取和用券截止时刻',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '商家停止活动后禁止新领和新用',
  KEY idx_coupon_campaign_merchant (merchant_id, starts_at),
  CHECK (discount_amount > 0 AND minimum_amount > discount_amount),
  CHECK (supply > 0 AND issued BETWEEN 0 AND supply),
  CHECK (starts_at < expires_at)
);

CREATE TABLE customer_coupons (
  id CHAR(36) PRIMARY KEY COMMENT '单张券的稳定标识',
  campaign_id CHAR(36) NOT NULL COMMENT '原活动版本',
  customer_id BIGINT NOT NULL COMMENT '领取人',
  status VARCHAR(12) NOT NULL COMMENT 'AVAILABLE、HELD、USED 或 EXPIRED',
  bound_quote CHAR(64) NULL COMMENT '当前绑定或核销的确认报价',
  claimed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_customer_coupon_campaign (customer_id,campaign_id),
  CONSTRAINT fk_customer_coupon_campaign FOREIGN KEY(campaign_id) REFERENCES coupon_campaigns(id),
  CHECK (status IN ('AVAILABLE','HELD','USED','EXPIRED'))
);

CREATE TABLE checkout_intents (
  quote_id CHAR(64) PRIMARY KEY COMMENT '已确认报价的 SHA-256，复用为下单幂等标识',
  customer_id BIGINT NOT NULL COMMENT '授权下单的顾客',
  scope_hash CHAR(64) NOT NULL COMMENT '确认请求范围摘要',
  order_number VARCHAR(40) NOT NULL COMMENT '恢复时复用的订单号',
  order_id BIGINT NULL COMMENT '订单服务生成的订单 ID',
  coupon_id CHAR(36) NULL COMMENT '可选顾客券实例',
  status VARCHAR(12) NOT NULL COMMENT 'PREPARED、BOUND、COMPLETE、USED 或 RETURNED',
  payload JSON NOT NULL COMMENT '确认时冻结的持久化下单数据',
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  pending_customer_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status = 'PREPARED' THEN customer_id END
  ) STORED,
  UNIQUE KEY uk_checkout_intent_pending_customer (pending_customer_id),
  UNIQUE KEY uk_checkout_intent_order (order_id),
  KEY idx_checkout_intent_recovery (status, next_attempt_at),
  CONSTRAINT fk_checkout_intent_coupon FOREIGN KEY(coupon_id) REFERENCES customer_coupons(id),
  CHECK (status IN ('PREPARED','BOUND','COMPLETE','USED','RETURNED'))
);

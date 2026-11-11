CREATE TABLE payment_merchant_accounts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  channel VARCHAR(30) NOT NULL,
  merchant_no VARCHAR(64) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  signing_algorithm VARCHAR(30) NOT NULL,
  api_secret VARCHAR(160) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_merchant_channel UNIQUE (channel),
  CONSTRAINT uk_payment_merchant_number UNIQUE (merchant_no)
);

INSERT INTO payment_merchant_accounts
  (channel, merchant_no, app_id, display_name, signing_algorithm, api_secret)
VALUES
  ('ALIPAY', 'ALIPAY_MERCHANT_10001', '2026111000000001', 'CommerceFlow Alipay Merchant', 'RSA_SHA256', 'demo-alipay-api-secret-not-for-production'),
  ('WECHAT_PAY', '1900001001', 'wx202611100001', 'CommerceFlow WeChat Pay Merchant', 'HMAC_SHA256', 'demo-wechat-api-secret-not-for-production'),
  ('UNION_PAY', '898310000000001', 'UP202611100001', 'CommerceFlow UnionPay Merchant', 'RSA_SHA256', 'demo-unionpay-api-secret-not-for-production');

ALTER TABLE payment_records ADD COLUMN merchant_account_id BIGINT NULL;
ALTER TABLE payment_records ADD COLUMN channel VARCHAR(30) NOT NULL DEFAULT 'LEGACY_SIMULATED';
ALTER TABLE payment_records ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE payment_records ADD COLUMN request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE payment_records ADD COLUMN channel_transaction_no VARCHAR(80) NULL;
ALTER TABLE payment_records ADD COLUMN paid_at TIMESTAMP(6) NULL;
ALTER TABLE payment_records ADD COLUMN expires_at TIMESTAMP(6) NULL;
ALTER TABLE payment_records ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE payment_records ADD CONSTRAINT fk_payment_merchant_account
  FOREIGN KEY (merchant_account_id) REFERENCES payment_merchant_accounts(id);
ALTER TABLE payment_records ADD CONSTRAINT uk_payment_channel_transaction
  UNIQUE (channel, channel_transaction_no);
ALTER TABLE payment_records ADD CONSTRAINT uk_payment_merchant_idempotency
  UNIQUE (merchant_account_id, idempotency_key);

CREATE TABLE payment_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_id BIGINT NULL,
  channel VARCHAR(30) NOT NULL,
  notification_id VARCHAR(100) NOT NULL,
  channel_transaction_no VARCHAR(80) NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  processing_status VARCHAR(30) NOT NULL,
  payload_digest VARCHAR(64) NOT NULL,
  failure_reason VARCHAR(300) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_notification UNIQUE (channel, notification_id),
  CONSTRAINT fk_payment_notification_payment FOREIGN KEY (payment_id) REFERENCES payment_records(id)
);

CREATE TABLE payment_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_id BIGINT NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  from_status VARCHAR(30) NOT NULL,
  to_status VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_event_id UNIQUE (event_id),
  CONSTRAINT fk_payment_event_payment FOREIGN KEY (payment_id) REFERENCES payment_records(id) ON DELETE CASCADE
);

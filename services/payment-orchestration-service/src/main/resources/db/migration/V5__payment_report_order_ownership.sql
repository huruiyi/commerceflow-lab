CREATE TABLE payment_report_order_ownership (
  order_id BIGINT NOT NULL COMMENT 'Stable order identifier from Order V1',
  merchant_id BIGINT NULL COMMENT 'First verified commerce merchant identifier',
  status VARCHAR(10) NOT NULL COMMENT 'Current ownership resolution as VERIFIED, MISSING or CONFLICT',
  PRIMARY KEY (order_id),
  KEY idx_payment_report_merchant (merchant_id, status, order_id),
  CONSTRAINT chk_payment_report_ownership_status CHECK (status IN ('VERIFIED', 'MISSING', 'CONFLICT')),
  CONSTRAINT chk_payment_report_verified CHECK (status <> 'VERIFIED' OR merchant_id IS NOT NULL),
  CONSTRAINT chk_payment_report_order_id CHECK (order_id > 0),
  CONSTRAINT chk_payment_report_merchant_id CHECK (merchant_id > 0)
);

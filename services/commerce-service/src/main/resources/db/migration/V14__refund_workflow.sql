ALTER TABLE payment_records
  ADD COLUMN refunded_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00;

CREATE TABLE refund_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_number VARCHAR(40) NOT NULL,
  order_id BIGINT NOT NULL,
  payment_id BIGINT NOT NULL,
  customer_id BIGINT NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  reason_code VARCHAR(50) NOT NULL,
  reason_detail VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL,
  order_status_before_refund VARCHAR(40) NOT NULL,
  channel_refund_no VARCHAR(100) NULL,
  failure_reason VARCHAR(300) NOT NULL DEFAULT '',
  requested_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  succeeded_at TIMESTAMP(6) NULL,
  failed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT uk_refund_number UNIQUE (refund_number),
  CONSTRAINT uk_refund_payment UNIQUE (payment_id),
  CONSTRAINT uk_refund_idempotency UNIQUE (payment_id, idempotency_key),
  CONSTRAINT uk_refund_channel_no UNIQUE (channel_refund_no),
  CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
  CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment_records(id),
  CONSTRAINT fk_refund_customer FOREIGN KEY (customer_id) REFERENCES app_users(id),
  CONSTRAINT chk_refund_amount CHECK (amount > 0)
);

CREATE TABLE refund_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_id BIGINT NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  from_status VARCHAR(30) NOT NULL,
  to_status VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_refund_event_id UNIQUE (event_id),
  CONSTRAINT fk_refund_event_refund FOREIGN KEY (refund_id) REFERENCES refund_records(id) ON DELETE CASCADE
);

CREATE TABLE refund_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_id BIGINT NULL,
  channel VARCHAR(30) NOT NULL,
  notification_id VARCHAR(100) NOT NULL,
  channel_refund_no VARCHAR(100) NOT NULL,
  signature_valid BOOLEAN NOT NULL,
  processing_status VARCHAR(30) NOT NULL,
  payload_digest VARCHAR(64) NOT NULL,
  failure_reason VARCHAR(300) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_refund_notification UNIQUE (channel, notification_id),
  CONSTRAINT fk_refund_notification_refund FOREIGN KEY (refund_id) REFERENCES refund_records(id)
);

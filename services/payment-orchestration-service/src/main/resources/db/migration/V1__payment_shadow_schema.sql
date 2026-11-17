CREATE TABLE payment_merchant_accounts (
  id BIGINT NOT NULL AUTO_INCREMENT, channel VARCHAR(30) NOT NULL, merchant_no VARCHAR(64) NOT NULL,
  app_id VARCHAR(64) NOT NULL, display_name VARCHAR(100) NOT NULL,
  signing_algorithm VARCHAR(30) NOT NULL, api_secret VARCHAR(160) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1, created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_merchant_channel(channel),
  UNIQUE KEY uk_payment_merchant_number(merchant_no)
);

CREATE TABLE payment_records (
  id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, payment_number VARCHAR(40) NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL, amount DECIMAL(12,2) NOT NULL, status VARCHAR(30) NOT NULL,
  failure_reason VARCHAR(200) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
  merchant_account_id BIGINT NULL, channel VARCHAR(30) NOT NULL, currency VARCHAR(3) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL, channel_transaction_no VARCHAR(80) NULL,
  paid_at TIMESTAMP(6) NULL, expires_at TIMESTAMP(6) NULL, updated_at TIMESTAMP(6) NOT NULL,
  checkout_url VARCHAR(500) NULL, action_type VARCHAR(30) NULL, qr_payload VARCHAR(500) NULL,
  version INT NOT NULL, refunded_amount DECIMAL(12,2) NOT NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_number(payment_number),
  UNIQUE KEY uk_payment_idempotency(idempotency_key),
  UNIQUE KEY uk_payment_channel_transaction(channel,channel_transaction_no),
  UNIQUE KEY uk_payment_merchant_idempotency(merchant_account_id,idempotency_key),
  KEY idx_payment_order(order_id),
  CONSTRAINT fk_shadow_payment_merchant FOREIGN KEY(merchant_account_id)
    REFERENCES payment_merchant_accounts(id)
);

CREATE TABLE payment_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT, payment_id BIGINT NULL, channel VARCHAR(30) NOT NULL,
  notification_id VARCHAR(100) NOT NULL, channel_transaction_no VARCHAR(80) NOT NULL,
  signature_valid TINYINT(1) NOT NULL, processing_status VARCHAR(30) NOT NULL,
  payload_digest VARCHAR(64) NOT NULL, failure_reason VARCHAR(300) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL, processed_at TIMESTAMP(6) NULL,
  raw_status VARCHAR(30) NULL, retry_count INT NOT NULL, next_retry_at TIMESTAMP(6) NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_payment_notification(channel,notification_id),
  KEY idx_payment_notification_payment(payment_id),
  CONSTRAINT fk_shadow_payment_notification FOREIGN KEY(payment_id) REFERENCES payment_records(id)
);

CREATE TABLE payment_events (
  id BIGINT NOT NULL AUTO_INCREMENT, payment_id BIGINT NOT NULL, event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(50) NOT NULL, from_status VARCHAR(30) NOT NULL, to_status VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_event_id(event_id), KEY idx_payment_event_payment(payment_id),
  CONSTRAINT fk_shadow_payment_event FOREIGN KEY(payment_id) REFERENCES payment_records(id)
    ON DELETE CASCADE
);

CREATE TABLE payment_idempotency_locks (
  idempotency_key VARCHAR(80) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(idempotency_key)
);

CREATE TABLE payment_reconciliation_issue (
  id BIGINT NOT NULL AUTO_INCREMENT, payment_id BIGINT NOT NULL, issue_type VARCHAR(50) NOT NULL,
  detail VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL, detected_at TIMESTAMP(6) NOT NULL,
  resolved_at TIMESTAMP(6) NULL, resolution_code VARCHAR(80) NULL, resolved_by VARCHAR(100) NULL,
  resolution_detail VARCHAR(500) NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_reconciliation_issue(payment_id,issue_type),
  CONSTRAINT fk_shadow_payment_reconciliation FOREIGN KEY(payment_id) REFERENCES payment_records(id)
);

CREATE TABLE payment_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(36) NOT NULL,
  aggregate_type VARCHAR(50) NOT NULL, aggregate_id VARCHAR(80) NOT NULL,
  event_type VARCHAR(80) NOT NULL, topic VARCHAR(100) NOT NULL, payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL, attempts INT NOT NULL, next_attempt_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6) NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_outbox_event(event_id), KEY idx_payment_outbox_dispatch(status,next_attempt_at)
);

CREATE TABLE kafka_consumed_event (
  id BIGINT NOT NULL AUTO_INCREMENT, consumer_group VARCHAR(100) NOT NULL,
  event_id VARCHAR(36) NOT NULL, topic VARCHAR(100) NOT NULL, processed_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_kafka_consumer_event(consumer_group,event_id)
);

CREATE TABLE refund_records (
  id BIGINT NOT NULL AUTO_INCREMENT, refund_number VARCHAR(40) NOT NULL, order_id BIGINT NOT NULL,
  payment_id BIGINT NOT NULL, customer_id BIGINT NOT NULL, idempotency_key VARCHAR(80) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL, amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL, reason_code VARCHAR(50) NOT NULL,
  reason_detail VARCHAR(500) NOT NULL, status VARCHAR(30) NOT NULL,
  inventory_status VARCHAR(40) NOT NULL, order_status_before_refund VARCHAR(40) NOT NULL,
  channel_refund_no VARCHAR(100) NULL, failure_reason VARCHAR(300) NOT NULL,
  requested_at TIMESTAMP(6) NOT NULL, succeeded_at TIMESTAMP(6) NULL, failed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version INT NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_refund_number(refund_number),
  UNIQUE KEY uk_refund_payment(payment_id),
  UNIQUE KEY uk_refund_idempotency(payment_id,idempotency_key),
  UNIQUE KEY uk_refund_channel_no(channel_refund_no), KEY idx_refund_order(order_id),
  KEY idx_refund_customer(customer_id),
  CONSTRAINT fk_shadow_refund_payment FOREIGN KEY(payment_id) REFERENCES payment_records(id),
  CONSTRAINT chk_shadow_refund_amount CHECK (amount > 0),
  CONSTRAINT chk_shadow_refund_inventory CHECK (inventory_status IN
    ('NOT_STARTED','WAITING','REFUNDED','RECONCILIATION_REQUIRED'))
);

CREATE TABLE refund_events (
  id BIGINT NOT NULL AUTO_INCREMENT, refund_id BIGINT NOT NULL, event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(50) NOT NULL, from_status VARCHAR(30) NOT NULL, to_status VARCHAR(30) NOT NULL,
  detail VARCHAR(500) NOT NULL, created_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_refund_event_id(event_id), KEY idx_refund_event_refund(refund_id),
  CONSTRAINT fk_shadow_refund_event FOREIGN KEY(refund_id) REFERENCES refund_records(id)
    ON DELETE CASCADE
);

CREATE TABLE refund_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT, refund_id BIGINT NULL, channel VARCHAR(30) NOT NULL,
  notification_id VARCHAR(100) NOT NULL, channel_refund_no VARCHAR(100) NOT NULL,
  signature_valid TINYINT(1) NOT NULL, processing_status VARCHAR(30) NOT NULL,
  payload_digest VARCHAR(64) NOT NULL, failure_reason VARCHAR(300) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL, processed_at TIMESTAMP(6) NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_refund_notification(channel,notification_id),
  KEY idx_refund_notification_refund(refund_id),
  CONSTRAINT fk_shadow_refund_notification FOREIGN KEY(refund_id) REFERENCES refund_records(id)
);

CREATE TABLE payment_process_managers (
  process_id VARCHAR(100) NOT NULL, order_id BIGINT NOT NULL, process_type VARCHAR(50) NOT NULL,
  status VARCHAR(40) NOT NULL, command_id VARCHAR(100) NOT NULL,
  command_fingerprint CHAR(64) NOT NULL, correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL, traceparent VARCHAR(100) NOT NULL,
  result_deadline_at TIMESTAMP(6) NOT NULL, next_check_at TIMESTAMP(6) NOT NULL,
  last_error VARCHAR(500) NULL, recovered_by VARCHAR(100) NULL,
  recovery_reason VARCHAR(300) NULL, recovered_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL, PRIMARY KEY(process_id),
  UNIQUE KEY uk_payment_process_command(command_id),
  KEY idx_payment_process_correlation(order_id,correlation_id),
  KEY idx_payment_process_recovery(status,next_check_at),
  CONSTRAINT chk_shadow_payment_process_status CHECK (status IN
    ('WAITING_RESULT','COMPLETED','COMPENSATION_REQUIRED','RECONCILIATION_REQUIRED'))
);

CREATE TABLE payment_command_outbox (
  command_id VARCHAR(100) NOT NULL, process_id VARCHAR(100) NOT NULL, order_id BIGINT NOT NULL,
  command_type VARCHAR(50) NOT NULL, payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL, status VARCHAR(30) NOT NULL, attempts INT NOT NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL, result_deadline_at TIMESTAMP(6) NOT NULL,
  last_error VARCHAR(500) NULL, correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL, traceparent VARCHAR(100) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, acknowledged_at TIMESTAMP(6) NULL, PRIMARY KEY(command_id),
  KEY idx_payment_command_delivery(status,next_attempt_at),
  KEY idx_payment_command_process(process_id),
  CONSTRAINT chk_shadow_payment_command_status CHECK (status IN
    ('NEW','IN_FLIGHT','RETRY','ACKNOWLEDGED','DEAD'))
);

CREATE TABLE payment_result_inbox (
  result_id VARCHAR(100) NOT NULL, process_id VARCHAR(100) NULL, order_id BIGINT NOT NULL,
  result_type VARCHAR(40) NOT NULL, outcome VARCHAR(40) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL, payload LONGTEXT NOT NULL,
  processing_status VARCHAR(30) NOT NULL, error_detail VARCHAR(500) NULL,
  correlation_id VARCHAR(100) NOT NULL, causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL, received_at TIMESTAMP(6) NOT NULL,
  processed_at TIMESTAMP(6) NULL, PRIMARY KEY(result_id),
  KEY idx_payment_result_process(process_id,received_at),
  KEY idx_payment_result_order(order_id,received_at),
  CONSTRAINT chk_shadow_payment_result_processing CHECK (processing_status IN
    ('RECEIVED','APPLIED','IGNORED','CONFLICT'))
);

CREATE TABLE payment_shadow_watermark (
  replica_name VARCHAR(50) NOT NULL, snapshot_generation BIGINT NOT NULL DEFAULT 0,
  source_sequence BIGINT NOT NULL DEFAULT 0, snapshot_completed_at TIMESTAMP(6) NULL,
  caught_up_at TIMESTAMP(6) NULL, PRIMARY KEY(replica_name)
);
INSERT INTO payment_shadow_watermark(replica_name) VALUES ('commerce-payment-v1');

CREATE TABLE payment_shadow_audit (
  id BIGINT NOT NULL AUTO_INCREMENT, source_sequence BIGINT NOT NULL,
  table_name VARCHAR(50) NOT NULL, source_count BIGINT NOT NULL, target_count BIGINT NOT NULL,
  source_fingerprint CHAR(64) NOT NULL, target_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL, audited_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id), KEY idx_payment_shadow_audit_time(audited_at)
);

CREATE TABLE payment_shadow_orphan_quarantine (
  id BIGINT NOT NULL AUTO_INCREMENT, change_sequence BIGINT NOT NULL,
  table_name VARCHAR(50) NOT NULL, row_key VARCHAR(100) NOT NULL,
  operation VARCHAR(10) NOT NULL, payload LONGTEXT NULL, reason VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN', attempts INT NOT NULL DEFAULT 0,
  quarantined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_at TIMESTAMP(6) NULL, PRIMARY KEY(id),
  UNIQUE KEY uk_payment_shadow_orphan_change(change_sequence)
);

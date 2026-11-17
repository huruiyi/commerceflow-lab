ALTER TABLE refund_records DROP FOREIGN KEY fk_refund_customer;

ALTER TABLE payment_reconciliation_issue
  ADD COLUMN resolution_code VARCHAR(80) NULL,
  ADD COLUMN resolved_by VARCHAR(100) NULL,
  ADD COLUMN resolution_detail VARCHAR(500) NULL;

UPDATE payment_reconciliation_issue issue
JOIN payment_records payment ON payment.id = issue.payment_id
SET issue.status = 'RESOLVED',
    issue.resolved_at = CURRENT_TIMESTAMP(6),
    issue.resolution_code = 'TERMINAL_PAYMENT_CONFIRMED',
    issue.resolved_by = 'flyway-v37-create-admission',
    issue.resolution_detail = CONCAT('Payment already reached terminal status ', payment.status)
WHERE issue.status = 'OPEN'
  AND issue.issue_type = 'PAYMENT_TIMEOUT'
  AND payment.status IN ('SUCCESS','FAILED','CLOSED','EXPIRED');

CREATE TABLE payment_process_managers (
  process_id VARCHAR(100) NOT NULL,
  order_id BIGINT NOT NULL,
  process_type VARCHAR(50) NOT NULL,
  status VARCHAR(40) NOT NULL,
  command_id VARCHAR(100) NOT NULL,
  command_fingerprint CHAR(64) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL,
  result_deadline_at TIMESTAMP(6) NOT NULL,
  next_check_at TIMESTAMP(6) NOT NULL,
  last_error VARCHAR(500) NULL,
  recovered_by VARCHAR(100) NULL,
  recovery_reason VARCHAR(300) NULL,
  recovered_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  completed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (process_id),
  UNIQUE KEY uk_payment_process_command (command_id),
  KEY idx_payment_process_correlation (order_id, correlation_id),
  KEY idx_payment_process_recovery (status, next_check_at),
  CONSTRAINT chk_payment_process_status CHECK (
    status IN ('WAITING_RESULT','COMPLETED','COMPENSATION_REQUIRED','RECONCILIATION_REQUIRED')
  )
);

CREATE TABLE payment_command_outbox (
  command_id VARCHAR(100) NOT NULL,
  process_id VARCHAR(100) NOT NULL,
  order_id BIGINT NOT NULL,
  command_type VARCHAR(50) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_deadline_at TIMESTAMP(6) NOT NULL,
  last_error VARCHAR(500) NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  acknowledged_at TIMESTAMP(6) NULL,
  PRIMARY KEY (command_id),
  KEY idx_payment_command_delivery (status, next_attempt_at),
  KEY idx_payment_command_process (process_id),
  CONSTRAINT chk_payment_command_status CHECK (
    status IN ('NEW','IN_FLIGHT','RETRY','ACKNOWLEDGED','DEAD')
  )
);

CREATE TABLE payment_result_inbox (
  result_id VARCHAR(100) NOT NULL,
  process_id VARCHAR(100) NULL,
  order_id BIGINT NOT NULL,
  result_type VARCHAR(40) NOT NULL,
  outcome VARCHAR(40) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL,
  processing_status VARCHAR(30) NOT NULL,
  error_detail VARCHAR(500) NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (result_id),
  KEY idx_payment_result_process (process_id, received_at),
  KEY idx_payment_result_order (order_id, received_at),
  CONSTRAINT chk_payment_result_processing CHECK (
    processing_status IN ('RECEIVED','APPLIED','IGNORED','CONFLICT')
  )
);

CREATE TABLE order_effect_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL,
  aggregate_id VARCHAR(80) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  topic VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_order_effect_outbox_event UNIQUE (event_id),
  KEY idx_order_effect_dispatch (status, next_attempt_at)
);

INSERT INTO order_effect_outbox(
  event_id,aggregate_id,event_type,topic,payload,status,attempts,next_attempt_at,created_at,published_at
)
SELECT event_id,aggregate_id,event_type,topic,payload,status,attempts,next_attempt_at,created_at,published_at
FROM payment_outbox
WHERE aggregate_type = 'ORDER';

DELETE FROM payment_outbox WHERE aggregate_type = 'ORDER';

CREATE TABLE order_effect_inbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consumer_group VARCHAR(100) NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  topic VARCHAR(100) NOT NULL,
  processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_order_effect_consumer_event UNIQUE (consumer_group,event_id)
);

INSERT INTO order_effect_inbox(consumer_group,event_id,topic,processed_at)
SELECT consumer_group,event_id,topic,processed_at
FROM kafka_consumed_event
WHERE consumer_group = 'commerceflow-order-effects';

DELETE FROM kafka_consumed_event WHERE topic = 'order-events';

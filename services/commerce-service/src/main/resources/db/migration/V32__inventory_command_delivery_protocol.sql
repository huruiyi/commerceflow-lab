CREATE TABLE inventory_command_delivery_outbox (
  command_id VARCHAR(100) NOT NULL,
  command_type VARCHAR(24) NOT NULL,
  producer VARCHAR(50) NOT NULL,
  order_id BIGINT NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_deadline_at TIMESTAMP(6) NULL,
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  acknowledged_at TIMESTAMP(6) NULL,
  PRIMARY KEY (command_id),
  KEY idx_inventory_delivery_dispatch (status,next_attempt_at),
  CONSTRAINT chk_inventory_delivery_type
    CHECK (command_type IN ('RESERVE','COMMIT','RELEASE','REFUND')),
  CONSTRAINT chk_inventory_delivery_status
    CHECK (status IN ('NEW','IN_FLIGHT','RETRY','ACKNOWLEDGED','RECONCILIATION_REQUIRED'))
);

CREATE TABLE inventory_command_result_inbox (
  result_id VARCHAR(100) NOT NULL,
  command_id VARCHAR(100) NOT NULL,
  command_type VARCHAR(24) NOT NULL,
  outcome VARCHAR(20) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  error_code VARCHAR(80) NULL,
  message VARCHAR(500) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(55) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (result_id),
  UNIQUE KEY uk_inventory_result_command (command_id),
  CONSTRAINT chk_inventory_result_type
    CHECK (command_type IN ('RESERVE','COMMIT','RELEASE','REFUND')),
  CONSTRAINT chk_inventory_result_outcome CHECK (outcome IN ('SUCCEEDED','REJECTED'))
);

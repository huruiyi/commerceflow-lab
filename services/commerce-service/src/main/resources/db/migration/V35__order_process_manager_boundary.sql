ALTER TABLE shop_orders DROP FOREIGN KEY fk_shop_orders_customer;
ALTER TABLE order_items DROP FOREIGN KEY fk_order_items_merchant;
ALTER TABLE order_events DROP FOREIGN KEY fk_order_events_actor;
ALTER TABLE order_events DROP FOREIGN KEY fk_order_events_scheduled_task;
ALTER TABLE customer_notifications DROP FOREIGN KEY fk_customer_notification_order;
ALTER TABLE payment_records DROP FOREIGN KEY fk_payment_order;
ALTER TABLE product_reviews DROP FOREIGN KEY fk_review_item;
ALTER TABLE refund_records DROP FOREIGN KEY fk_refund_order;
ALTER TABLE shipment_records DROP FOREIGN KEY fk_storefront_shipment_order;

CREATE TABLE order_process_managers (
  process_id VARCHAR(100) NOT NULL,
  order_id BIGINT NOT NULL,
  process_type VARCHAR(50) NOT NULL,
  status VARCHAR(40) NOT NULL,
  current_step VARCHAR(50) NOT NULL,
  waiting_for VARCHAR(30) NOT NULL,
  command_id VARCHAR(100) NOT NULL,
  command_fingerprint CHAR(64) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
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
  UNIQUE KEY uk_order_process_command (command_id),
  KEY idx_order_process_recovery (status, next_check_at),
  KEY idx_order_process_order (order_id, created_at),
  CONSTRAINT chk_order_process_status CHECK (
    status IN ('WAITING_RESULT','COMPLETED','COMPENSATION_REQUIRED','RECONCILIATION_REQUIRED')
  )
);

CREATE TABLE order_command_outbox (
  command_id VARCHAR(100) NOT NULL,
  process_id VARCHAR(100) NOT NULL,
  order_id BIGINT NOT NULL,
  target_service VARCHAR(50) NOT NULL,
  command_type VARCHAR(50) NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  result_deadline_at TIMESTAMP(6) NOT NULL,
  lease_until TIMESTAMP(6) NULL,
  last_error VARCHAR(500) NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  acknowledged_at TIMESTAMP(6) NULL,
  PRIMARY KEY (command_id),
  KEY idx_order_command_delivery (status, next_attempt_at),
  KEY idx_order_command_process (process_id),
  CONSTRAINT chk_order_command_status CHECK (
    status IN ('NEW','IN_FLIGHT','RETRY','ACKNOWLEDGED','DEAD')
  )
);

CREATE TABLE order_result_inbox (
  result_id VARCHAR(100) NOT NULL,
  process_id VARCHAR(100) NULL,
  order_id BIGINT NOT NULL,
  result_type VARCHAR(30) NOT NULL,
  outcome VARCHAR(30) NOT NULL,
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
  KEY idx_order_result_process (process_id, received_at),
  KEY idx_order_result_order (order_id, received_at),
  CONSTRAINT chk_order_result_processing CHECK (
    processing_status IN ('RECEIVED','APPLIED','IGNORED','CONFLICT')
  )
);

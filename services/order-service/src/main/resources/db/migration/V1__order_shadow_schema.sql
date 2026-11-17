CREATE TABLE shop_orders (
  id BIGINT NOT NULL, order_number VARCHAR(40) NOT NULL, customer_id BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL, inventory_status VARCHAR(40) NOT NULL, total_amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
  confirmed_at TIMESTAMP(6) NULL, shipped_at TIMESTAMP(6) NULL, carrier VARCHAR(100) NULL,
  tracking_number VARCHAR(120) NULL, payment_due_at TIMESTAMP(6) NOT NULL, cancelled_at TIMESTAMP(6) NULL,
  cancel_reason VARCHAR(300) NULL, received_at TIMESTAMP(6) NULL, completed_at TIMESTAMP(6) NULL,
  logistics_delivered_at TIMESTAMP(6) NULL, PRIMARY KEY(id), UNIQUE KEY uk_shop_orders_number(order_number)
);
CREATE TABLE order_items (
  id BIGINT NOT NULL, order_id BIGINT NOT NULL, product_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL,
  product_name VARCHAR(160) NOT NULL, thumbnail_url VARCHAR(500) NOT NULL, unit_price DECIMAL(12,2) NOT NULL,
  quantity INT NOT NULL, subtotal DECIMAL(12,2) NOT NULL, PRIMARY KEY(id), KEY idx_order_items_order(order_id),
  CONSTRAINT fk_shadow_item_order FOREIGN KEY(order_id) REFERENCES shop_orders(id) ON DELETE CASCADE
);
CREATE TABLE order_shipping_addresses (
  order_id BIGINT NOT NULL, recipient_name VARCHAR(100) NOT NULL, phone VARCHAR(40) NOT NULL,
  country_code VARCHAR(2) NOT NULL, state_province VARCHAR(100) NOT NULL, city VARCHAR(100) NOT NULL,
  district VARCHAR(100) NOT NULL, address_line1 VARCHAR(300) NOT NULL, address_line2 VARCHAR(300) NOT NULL,
  postal_code VARCHAR(20) NOT NULL, label VARCHAR(40) NOT NULL, PRIMARY KEY(order_id),
  CONSTRAINT fk_shadow_address_order FOREIGN KEY(order_id) REFERENCES shop_orders(id) ON DELETE CASCADE
);
CREATE TABLE order_events (
  id BIGINT NOT NULL, order_id BIGINT NOT NULL, actor_id BIGINT NULL, event_type VARCHAR(50) NOT NULL,
  from_status VARCHAR(40) NOT NULL, to_status VARCHAR(40) NOT NULL, detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, trigger_source VARCHAR(40) NOT NULL, scheduled_task_id BIGINT NULL,
  PRIMARY KEY(id), KEY idx_order_events_order(order_id),
  CONSTRAINT fk_shadow_event_order FOREIGN KEY(order_id) REFERENCES shop_orders(id) ON DELETE CASCADE
);
CREATE TABLE order_process_managers (
  process_id VARCHAR(100) NOT NULL, order_id BIGINT NOT NULL, process_type VARCHAR(50) NOT NULL,
  status VARCHAR(40) NOT NULL, current_step VARCHAR(50) NOT NULL, waiting_for VARCHAR(30) NOT NULL,
  command_id VARCHAR(100) NOT NULL, command_fingerprint CHAR(64) NOT NULL, correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL, traceparent VARCHAR(100) NOT NULL, attempts INT NOT NULL,
  result_deadline_at TIMESTAMP(6) NOT NULL, next_check_at TIMESTAMP(6) NOT NULL, last_error VARCHAR(500) NULL,
  recovered_by VARCHAR(100) NULL, recovery_reason VARCHAR(300) NULL, recovered_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL, PRIMARY KEY(process_id), UNIQUE KEY uk_order_process_command(command_id)
);
CREATE TABLE order_command_outbox (
  command_id VARCHAR(100) NOT NULL, process_id VARCHAR(100) NOT NULL, order_id BIGINT NOT NULL,
  target_service VARCHAR(50) NOT NULL, command_type VARCHAR(50) NOT NULL, payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL, status VARCHAR(30) NOT NULL, attempts INT NOT NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL, result_deadline_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6) NULL,
  last_error VARCHAR(500) NULL, correlation_id VARCHAR(100) NOT NULL, causation_id VARCHAR(100) NOT NULL,
  traceparent VARCHAR(100) NOT NULL, created_at TIMESTAMP(6) NOT NULL, acknowledged_at TIMESTAMP(6) NULL,
  PRIMARY KEY(command_id), KEY idx_order_command_process(process_id)
);
CREATE TABLE order_result_inbox (
  result_id VARCHAR(100) NOT NULL, process_id VARCHAR(100) NULL, order_id BIGINT NOT NULL,
  result_type VARCHAR(30) NOT NULL, outcome VARCHAR(30) NOT NULL, payload_fingerprint CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL, processing_status VARCHAR(30) NOT NULL, error_detail VARCHAR(500) NULL,
  correlation_id VARCHAR(100) NOT NULL, causation_id VARCHAR(100) NOT NULL, traceparent VARCHAR(100) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL, processed_at TIMESTAMP(6) NULL, PRIMARY KEY(result_id)
);
CREATE TABLE order_shadow_watermark (
  replica_name VARCHAR(40) NOT NULL, snapshot_generation BIGINT NOT NULL DEFAULT 0,
  source_sequence BIGINT NOT NULL DEFAULT 0, snapshot_completed_at TIMESTAMP(6) NULL,
  caught_up_at TIMESTAMP(6) NULL, PRIMARY KEY(replica_name)
);
INSERT INTO order_shadow_watermark(replica_name) VALUES ('commerce-order-v1');
CREATE TABLE order_shadow_audit (
  id BIGINT NOT NULL AUTO_INCREMENT, source_sequence BIGINT NOT NULL, table_name VARCHAR(50) NOT NULL,
  source_count BIGINT NOT NULL, target_count BIGINT NOT NULL, source_fingerprint CHAR(64) NOT NULL,
  target_fingerprint CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, audited_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id), KEY idx_order_shadow_audit_time(audited_at)
);
CREATE TABLE order_shadow_orphan_quarantine (
  id BIGINT NOT NULL AUTO_INCREMENT, change_sequence BIGINT NOT NULL, table_name VARCHAR(50) NOT NULL,
  row_key VARCHAR(100) NOT NULL, operation VARCHAR(10) NOT NULL, payload LONGTEXT NULL,
  reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', attempts INT NOT NULL DEFAULT 0,
  quarantined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), resolved_at TIMESTAMP(6) NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_order_shadow_orphan_change(change_sequence)
);

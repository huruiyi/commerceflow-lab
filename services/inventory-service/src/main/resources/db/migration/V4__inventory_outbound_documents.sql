CREATE TABLE inventory_outbound_documents (
  outbound_id CHAR(36) NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  posted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_inventory_outbound_order (order_id)
);

CREATE TABLE inventory_outbound_lines (
  outbound_id CHAR(36) NOT NULL,
  order_line_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  PRIMARY KEY (outbound_id, order_line_id),
  UNIQUE KEY uk_inventory_outbound_order_line (order_line_id),
  CONSTRAINT fk_inventory_outbound_document
    FOREIGN KEY (outbound_id) REFERENCES inventory_outbound_documents(outbound_id),
  CONSTRAINT chk_inventory_outbound_quantity CHECK (quantity > 0)
);

CREATE TABLE inventory_outbound_event_outbox (
  event_id VARCHAR(100) NOT NULL PRIMARY KEY,
  outbound_id CHAR(36) NOT NULL,
  order_line_id BIGINT NOT NULL,
  payload JSON NOT NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  KEY idx_inventory_outbound_event_pending (published_at, next_attempt_at, event_id),
  CONSTRAINT fk_inventory_outbound_event_line
    FOREIGN KEY (outbound_id, order_line_id) REFERENCES inventory_outbound_lines(outbound_id, order_line_id)
);

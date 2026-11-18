CREATE TABLE inventory_delivered_reconciliation (
  order_id BIGINT PRIMARY KEY,
  evidence_fingerprint CHAR(64) NOT NULL,
  recorded_by VARCHAR(100) NOT NULL,
  recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE inventory_delivered_reconciliation_lines (
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  warehouse_reference VARCHAR(120) NOT NULL,
  evidence_at TIMESTAMP(6) NOT NULL,
  evidence_note VARCHAR(300) NOT NULL,
  movement_id BIGINT NOT NULL,
  disposition VARCHAR(30) NOT NULL,
  PRIMARY KEY (order_id, product_id),
  UNIQUE (warehouse_reference),
  UNIQUE (movement_id),
  FOREIGN KEY (order_id) REFERENCES inventory_delivered_reconciliation(order_id),
  FOREIGN KEY (movement_id) REFERENCES inventory_movements(id),
  CHECK (quantity > 0),
  CHECK (disposition IN ('POSTED_MISSING','ALREADY_BOOKED'))
);

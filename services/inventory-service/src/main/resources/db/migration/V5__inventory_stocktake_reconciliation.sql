CREATE TABLE inventory_stocktake_reconciliation (
  reference VARCHAR(80) PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL,
  audit_data JSON NOT NULL,
  adjustment INT NOT NULL,
  movement_id BIGINT NULL,
  applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (movement_id) REFERENCES inventory_movements(id)
);

CREATE TABLE inventory_stocktake_orders (
  order_id BIGINT PRIMARY KEY,
  reference VARCHAR(80) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES inventory_delivered_reconciliation(order_id),
  FOREIGN KEY (reference) REFERENCES inventory_stocktake_reconciliation(reference)
);

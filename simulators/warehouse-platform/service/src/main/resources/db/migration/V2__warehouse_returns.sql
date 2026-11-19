CREATE TABLE warehouse_returns (
  return_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  outbound_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id BIGINT NOT NULL,
  order_line_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  expected_quantity INT NOT NULL,
  received_quantity INT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'WAITING_RECEIPT',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  received_at TIMESTAMP(6) NULL,
  inspected_at TIMESTAMP(6) NULL,
  CHECK (expected_quantity > 0 AND (received_quantity IS NULL OR received_quantity BETWEEN 0 AND expected_quantity)),
  CHECK (status IN ('WAITING_RECEIPT','RECEIVED','PASSED','FAILED','SHORT_RECEIVED','REJECTED')),
  INDEX idx_warehouse_return_orders (order_id,created_at)
);

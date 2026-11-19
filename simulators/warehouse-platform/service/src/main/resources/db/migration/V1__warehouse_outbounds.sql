CREATE TABLE warehouse_outbounds (
  outbound_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  whole_order BOOLEAN NOT NULL,
  planned JSON NOT NULL,
  actual JSON NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  decided_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_warehouse_outbound_status CHECK (status IN ('PENDING','DISPATCHED','VOIDED')),
  INDEX idx_warehouse_outbound_orders (order_id,created_at)
);

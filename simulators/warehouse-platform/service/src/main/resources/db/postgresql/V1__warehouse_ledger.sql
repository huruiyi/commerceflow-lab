CREATE TABLE warehouse_outbounds (
  outbound_id VARCHAR(36) COLLATE "C" PRIMARY KEY,
  order_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  whole_order BOOLEAN NOT NULL,
  planned JSONB NOT NULL,
  actual JSONB,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMPTZ(6),
  CONSTRAINT chk_warehouse_outbound_status CHECK (status IN ('PENDING','DISPATCHED','VOIDED'))
);
CREATE INDEX idx_warehouse_outbound_orders ON warehouse_outbounds (order_id,created_at);
CREATE INDEX idx_warehouse_outbound_created ON warehouse_outbounds (created_at DESC,outbound_id);
CREATE INDEX idx_warehouse_outbound_status_created ON warehouse_outbounds (status,created_at DESC,outbound_id);

CREATE TABLE warehouse_returns (
  return_id VARCHAR(36) COLLATE "C" PRIMARY KEY,
  outbound_id VARCHAR(36) COLLATE "C" NOT NULL,
  order_id BIGINT NOT NULL,
  order_line_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  tracking_number VARCHAR(120) NOT NULL,
  expected_quantity INTEGER NOT NULL,
  received_quantity INTEGER,
  status VARCHAR(24) NOT NULL DEFAULT 'WAITING_RECEIPT',
  created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  received_at TIMESTAMPTZ(6),
  inspected_at TIMESTAMPTZ(6),
  supplement_id VARCHAR(36),
  qualified_quantity INTEGER,
  supplement_note VARCHAR(300),
  supplement_at TIMESTAMPTZ(6),
  CHECK (expected_quantity > 0 AND (received_quantity IS NULL OR received_quantity BETWEEN 0 AND expected_quantity)),
  CHECK (status IN ('WAITING_RECEIPT','RECEIVED','PASSED','FAILED','SHORT_RECEIVED','REJECTED')),
  CONSTRAINT ck_return_supplement CHECK (
    (supplement_id IS NULL AND qualified_quantity IS NULL AND supplement_note IS NULL AND supplement_at IS NULL)
    OR (supplement_id IS NOT NULL AND qualified_quantity IS NOT NULL AND received_quantity IS NOT NULL
      AND qualified_quantity BETWEEN 1 AND received_quantity AND supplement_note IS NOT NULL
      AND supplement_at IS NOT NULL AND status IN ('SHORT_RECEIVED','FAILED'))
  )
);
CREATE INDEX idx_warehouse_return_orders ON warehouse_returns (order_id,created_at);
CREATE INDEX idx_warehouse_return_created ON warehouse_returns (created_at DESC,return_id);
CREATE INDEX idx_warehouse_return_status_created ON warehouse_returns (status,created_at DESC,return_id);

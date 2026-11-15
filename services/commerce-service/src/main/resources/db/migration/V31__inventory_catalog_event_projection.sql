ALTER TABLE product_inventory
  ADD COLUMN catalog_lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER unit_price,
  ADD COLUMN catalog_business_version BIGINT NOT NULL DEFAULT 1
    AFTER catalog_lifecycle_status,
  ADD CONSTRAINT chk_inventory_catalog_lifecycle
    CHECK (catalog_lifecycle_status IN ('ACTIVE','INACTIVE'));

CREATE TABLE inventory_catalog_event_inbox (
  event_id VARCHAR(100) NOT NULL,
  product_id BIGINT NOT NULL,
  business_version BIGINT NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (event_id),
  KEY idx_inventory_catalog_product (product_id,business_version),
  CONSTRAINT chk_inventory_catalog_event_status
    CHECK (status IN ('PROCESSING','APPLIED','STALE'))
);

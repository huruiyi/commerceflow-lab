ALTER TABLE products
  ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER merchant_id,
  ADD COLUMN business_version BIGINT NOT NULL DEFAULT 1 AFTER lifecycle_status,
  ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6) AFTER business_version,
  ADD CONSTRAINT chk_product_lifecycle_status
    CHECK (lifecycle_status IN ('ACTIVE','INACTIVE'));

CREATE TABLE catalog_event_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  schema_version INT NOT NULL,
  business_version BIGINT NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  causation_id VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_catalog_event_id UNIQUE (event_id),
  CONSTRAINT chk_catalog_event_status CHECK (status IN ('NEW','RETRY','PUBLISHED','DEAD')),
  KEY ix_catalog_event_dispatch (status,next_attempt_at),
  KEY ix_catalog_event_aggregate (aggregate_id,business_version)
);

CREATE TABLE products (
  id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  brief VARCHAR(300) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  merchant_id BIGINT NOT NULL,
  lifecycle_status VARCHAR(20) NOT NULL,
  business_version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT chk_catalog_product_status CHECK (lifecycle_status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE product_translations (
  product_id BIGINT NOT NULL,
  locale VARCHAR(10) NOT NULL,
  name VARCHAR(160) NOT NULL,
  brief VARCHAR(300) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  PRIMARY KEY (product_id,locale),
  CONSTRAINT fk_catalog_translation_product FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE
);

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
  CONSTRAINT uk_catalog_service_event UNIQUE (event_id),
  CONSTRAINT chk_catalog_service_event_status CHECK (status IN ('NEW','RETRY','PUBLISHED','DEAD')),
  KEY ix_catalog_service_event_dispatch (status,next_attempt_at),
  KEY ix_catalog_service_event_aggregate (aggregate_id,business_version)
);

CREATE TABLE catalog_cutover_state (
  cutover_key VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL,
  source_version INT NOT NULL,
  product_count INT NOT NULL,
  translation_count INT NOT NULL,
  product_fingerprint BIGINT UNSIGNED NOT NULL,
  translation_fingerprint BIGINT UNSIGNED NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (cutover_key),
  CONSTRAINT chk_catalog_cutover_status CHECK (status IN ('RUNNING','COMPLETE','FAILED'))
);

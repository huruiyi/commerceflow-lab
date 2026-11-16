CREATE TABLE product_inventory (
  product_id BIGINT NOT NULL,
  merchant_id BIGINT NULL,
  product_name VARCHAR(160) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  catalog_lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  catalog_business_version BIGINT NOT NULL DEFAULT 1,
  stock_available INT NOT NULL,
  stock_reserved INT NOT NULL,
  stock_sold INT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (product_id),
  KEY idx_product_inventory_merchant (merchant_id),
  CONSTRAINT chk_inventory_catalog_lifecycle
    CHECK (catalog_lifecycle_status IN ('ACTIVE','INACTIVE')),
  CONSTRAINT chk_inventory_balances
    CHECK (stock_available >= 0 AND stock_reserved >= 0 AND stock_sold >= 0)
);

CREATE TABLE inventory_reservations (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  reserved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  committed_at TIMESTAMP(6) NULL,
  released_at TIMESTAMP(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_reservation_order_product (order_id,product_id),
  KEY idx_inventory_reservation_product (product_id),
  CONSTRAINT fk_inventory_reservation_product
    FOREIGN KEY (product_id) REFERENCES product_inventory(product_id),
  CONSTRAINT chk_inventory_reservation_quantity CHECK (quantity > 0),
  CONSTRAINT chk_inventory_reservation_status
    CHECK (status IN ('RESERVED','COMMITTED','RELEASED','REFUNDED'))
);

CREATE TABLE inventory_movements (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(100) NOT NULL,
  order_id BIGINT NULL,
  product_id BIGINT NOT NULL,
  movement_type VARCHAR(32) NOT NULL,
  quantity INT NOT NULL,
  available_after INT NOT NULL,
  reserved_after INT NOT NULL,
  sold_after INT NOT NULL,
  detail VARCHAR(300) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_movement_event (event_id),
  KEY idx_inventory_movement_order (order_id),
  KEY idx_inventory_movement_product (product_id),
  CONSTRAINT fk_inventory_movement_product
    FOREIGN KEY (product_id) REFERENCES product_inventory(product_id)
);

CREATE TABLE inventory_command_inbox (
  command_id VARCHAR(100) NOT NULL,
  command_type VARCHAR(24) NOT NULL,
  order_id BIGINT NOT NULL,
  payload_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  processed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (command_id),
  KEY idx_inventory_command_order (order_id,created_at),
  CONSTRAINT chk_inventory_command_type
    CHECK (command_type IN ('RESERVE','COMMIT','RELEASE','REFUND')),
  CONSTRAINT chk_inventory_command_status
    CHECK (status IN ('PROCESSING','SUCCEEDED'))
);

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

CREATE TABLE inventory_migration_state (
  migration_key VARCHAR(60) NOT NULL,
  status VARCHAR(20) NOT NULL,
  source_version INT NOT NULL,
  generation INT NOT NULL DEFAULT 0,
  source_fingerprint VARCHAR(500) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  PRIMARY KEY (migration_key),
  CONSTRAINT chk_inventory_migration_status
    CHECK (status IN ('RUNNING','READY','FAILED','CUTOVER_COMPLETE'))
);

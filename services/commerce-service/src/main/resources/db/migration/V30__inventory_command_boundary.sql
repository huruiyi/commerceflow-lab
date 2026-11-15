ALTER TABLE inventory_reservations
  DROP FOREIGN KEY fk_inventory_reservation_order;

ALTER TABLE inventory_movements
  DROP FOREIGN KEY fk_inventory_movement_order;

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

ALTER TABLE products
  ADD COLUMN stock_available INT NOT NULL DEFAULT 100,
  ADD COLUMN stock_reserved INT NOT NULL DEFAULT 0,
  ADD COLUMN stock_sold INT NOT NULL DEFAULT 0;

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
  CONSTRAINT uk_inventory_reservation_order_product UNIQUE (order_id, product_id),
  CONSTRAINT fk_inventory_reservation_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
  CONSTRAINT fk_inventory_reservation_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT chk_inventory_reservation_quantity CHECK (quantity > 0)
);

CREATE TABLE inventory_movements (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(100) NOT NULL,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  movement_type VARCHAR(32) NOT NULL,
  quantity INT NOT NULL,
  available_after INT NOT NULL,
  reserved_after INT NOT NULL,
  sold_after INT NOT NULL,
  detail VARCHAR(300) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_inventory_movement_event UNIQUE (event_id),
  CONSTRAINT fk_inventory_movement_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
  CONSTRAINT fk_inventory_movement_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE customer_notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(100) NOT NULL,
  customer_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  notification_type VARCHAR(50) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
  read_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_customer_notification_event UNIQUE (event_id),
  CONSTRAINT fk_customer_notification_customer FOREIGN KEY (customer_id) REFERENCES app_users(id),
  CONSTRAINT fk_customer_notification_order FOREIGN KEY (order_id) REFERENCES shop_orders(id)
);

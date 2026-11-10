ALTER TABLE products ADD COLUMN merchant_id BIGINT NULL;
UPDATE products SET merchant_id=(SELECT id FROM app_users WHERE email='merchant@example.com');
ALTER TABLE products ADD CONSTRAINT fk_products_merchant FOREIGN KEY (merchant_id) REFERENCES app_users(id);

CREATE TABLE shop_orders (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_number VARCHAR(40) NOT NULL,
  customer_id BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL,
  total_amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_shop_orders_number UNIQUE (order_number),
  CONSTRAINT fk_shop_orders_customer FOREIGN KEY (customer_id) REFERENCES app_users(id)
);

CREATE TABLE order_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  product_name VARCHAR(160) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  quantity INTEGER NOT NULL,
  subtotal DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES shop_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT fk_order_items_merchant FOREIGN KEY (merchant_id) REFERENCES app_users(id)
);

CREATE TABLE order_shipping_addresses (
  order_id BIGINT NOT NULL,
  recipient_name VARCHAR(100) NOT NULL,
  phone VARCHAR(40) NOT NULL,
  country_code VARCHAR(2) NOT NULL,
  state_province VARCHAR(100) NOT NULL,
  city VARCHAR(100) NOT NULL,
  district VARCHAR(100) NOT NULL,
  address_line1 VARCHAR(300) NOT NULL,
  address_line2 VARCHAR(300) NOT NULL,
  postal_code VARCHAR(20) NOT NULL,
  label VARCHAR(40) NOT NULL,
  PRIMARY KEY (order_id),
  CONSTRAINT fk_order_address_order FOREIGN KEY (order_id) REFERENCES shop_orders(id) ON DELETE CASCADE
);

CREATE TABLE payment_records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  payment_number VARCHAR(40) NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(30) NOT NULL,
  failure_reason VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_number UNIQUE (payment_number),
  CONSTRAINT uk_payment_idempotency UNIQUE (idempotency_key),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES shop_orders(id)
);

CREATE TABLE order_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  event_type VARCHAR(50) NOT NULL,
  from_status VARCHAR(40) NOT NULL,
  to_status VARCHAR(40) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_order_events_order FOREIGN KEY (order_id) REFERENCES shop_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_order_events_actor FOREIGN KEY (actor_id) REFERENCES app_users(id)
);

CREATE TABLE carts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_carts_user UNIQUE (user_id),
  CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE TABLE cart_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cart_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INTEGER NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_cart_items_product UNIQUE (cart_id, product_id),
  CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
  CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
  CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 99)
);

INSERT INTO carts (user_id, created_at, updated_at)
SELECT DISTINCT user_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM customer_orders;

INSERT INTO cart_items (cart_id, product_id, quantity, created_at, updated_at)
SELECT c.id, o.product_id, LEAST(SUM(o.quantity), 99), MIN(o.created_at), MAX(o.created_at)
FROM customer_orders o JOIN carts c ON c.user_id = o.user_id
GROUP BY c.id, o.product_id;

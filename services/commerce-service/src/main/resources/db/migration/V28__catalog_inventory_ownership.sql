CREATE TABLE product_inventory (
  product_id BIGINT NOT NULL,
  merchant_id BIGINT NULL,
  product_name VARCHAR(160) NOT NULL,
  thumbnail_url VARCHAR(500) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  stock_available INT NOT NULL,
  stock_reserved INT NOT NULL,
  stock_sold INT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (product_id),
  KEY idx_product_inventory_merchant (merchant_id)
);

INSERT INTO product_inventory(
  product_id,merchant_id,product_name,thumbnail_url,unit_price,
  stock_available,stock_reserved,stock_sold
)
SELECT id,merchant_id,name,thumbnail_url,price,stock_available,stock_reserved,stock_sold
FROM products;

ALTER TABLE inventory_reservations
  DROP FOREIGN KEY fk_inventory_reservation_product,
  ADD CONSTRAINT fk_inventory_reservation_inventory
    FOREIGN KEY (product_id) REFERENCES product_inventory(product_id);

ALTER TABLE inventory_movements
  DROP FOREIGN KEY fk_inventory_movement_product,
  ADD CONSTRAINT fk_inventory_movement_inventory
    FOREIGN KEY (product_id) REFERENCES product_inventory(product_id);

ALTER TABLE cart_items
  ADD COLUMN merchant_id BIGINT NULL AFTER product_id,
  ADD COLUMN product_name VARCHAR(160) NULL AFTER merchant_id,
  ADD COLUMN thumbnail_url VARCHAR(500) NULL AFTER product_name,
  ADD COLUMN unit_price DECIMAL(12,2) NULL AFTER thumbnail_url;

UPDATE cart_items i
JOIN products p ON p.id=i.product_id
SET i.merchant_id=p.merchant_id,
    i.product_name=p.name,
    i.thumbnail_url=p.thumbnail_url,
    i.unit_price=p.price;

ALTER TABLE cart_items
  MODIFY COLUMN product_name VARCHAR(160) NOT NULL,
  MODIFY COLUMN thumbnail_url VARCHAR(500) NOT NULL,
  MODIFY COLUMN unit_price DECIMAL(12,2) NOT NULL,
  DROP FOREIGN KEY fk_cart_items_product;

ALTER TABLE customer_orders
  ADD COLUMN product_name VARCHAR(160) NULL AFTER product_id,
  ADD COLUMN thumbnail_url VARCHAR(500) NULL AFTER product_name,
  ADD COLUMN unit_price DECIMAL(12,2) NULL AFTER thumbnail_url;

UPDATE customer_orders o
JOIN products p ON p.id=o.product_id
SET o.product_name=p.name,
    o.thumbnail_url=p.thumbnail_url,
    o.unit_price=p.price;

ALTER TABLE customer_orders
  MODIFY COLUMN product_name VARCHAR(160) NOT NULL,
  MODIFY COLUMN thumbnail_url VARCHAR(500) NOT NULL,
  MODIFY COLUMN unit_price DECIMAL(12,2) NOT NULL,
  DROP FOREIGN KEY fk_customer_orders_product;

ALTER TABLE order_items DROP FOREIGN KEY fk_order_items_product;
ALTER TABLE product_reviews DROP FOREIGN KEY fk_review_product;
ALTER TABLE products DROP FOREIGN KEY fk_products_merchant;

ALTER TABLE products
  DROP COLUMN stock_available,
  DROP COLUMN stock_reserved,
  DROP COLUMN stock_sold;

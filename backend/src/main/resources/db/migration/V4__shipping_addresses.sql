CREATE TABLE shipping_addresses (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  recipient_name VARCHAR(100) NOT NULL,
  phone VARCHAR(40) NOT NULL,
  country_code VARCHAR(2) NOT NULL,
  state_province VARCHAR(100) NOT NULL,
  city VARCHAR(100) NOT NULL,
  district VARCHAR(100) NOT NULL,
  address_line1 VARCHAR(300) NOT NULL,
  address_line2 VARCHAR(300) NULL,
  postal_code VARCHAR(20) NOT NULL,
  label VARCHAR(40) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  INDEX idx_shipping_addresses_user (user_id),
  CONSTRAINT fk_shipping_addresses_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE
);

ALTER TABLE carts ADD COLUMN selected_address_id BIGINT NULL;
ALTER TABLE carts ADD CONSTRAINT fk_carts_selected_address
  FOREIGN KEY (selected_address_id) REFERENCES shipping_addresses (id) ON DELETE SET NULL;

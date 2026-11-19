ALTER TABLE product_translations DROP FOREIGN KEY fk_catalog_translation_product;

ALTER TABLE products MODIFY id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN create_request_id CHAR(36) NULL COMMENT 'Stable merchant product creation request',
  ADD COLUMN create_fingerprint CHAR(64) NULL COMMENT 'Immutable normalized creation payload digest',
  ADD UNIQUE KEY uk_catalog_merchant_creation(merchant_id,create_request_id);

ALTER TABLE product_translations
  ADD CONSTRAINT fk_catalog_translation_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE;

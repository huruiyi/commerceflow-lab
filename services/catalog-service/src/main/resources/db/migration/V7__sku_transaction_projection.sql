ALTER TABLE products
  ADD COLUMN listing_product_id BIGINT NULL COMMENT '内部销售单元对应的公开商品，公开商品自身为空',
  ADD KEY ix_catalog_listing_product(listing_product_id,id),
  ADD CONSTRAINT fk_catalog_listing_product FOREIGN KEY(listing_product_id) REFERENCES products(id);

UPDATE catalog_search_state SET rebuild_requested=TRUE WHERE singleton_id=1;

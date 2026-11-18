START TRANSACTION;
CREATE TEMPORARY TABLE demo_guard (verified INT PRIMARY KEY);
INSERT INTO demo_guard VALUES (0);
INSERT INTO demo_guard VALUES (COALESCE(GET_LOCK('commerceflow.catalog.bootstrap', 30),0));

SET @seeded = EXISTS (
  SELECT 1 FROM catalog_cutover_state WHERE cutover_key='catalog-demo-v1' AND status='COMPLETE'
);
SELECT COUNT(*) INTO @products FROM products FOR UPDATE;
SELECT COUNT(*) INTO @translations FROM product_translations FOR UPDATE;
SELECT COUNT(*) INTO @events FROM catalog_event_outbox FOR UPDATE;
INSERT INTO demo_guard
SELECT IF(@seeded OR (
  NOT EXISTS (SELECT 1 FROM catalog_cutover_state)
  AND @products=0 AND @translations=0 AND @events=0
),2,0);

INSERT INTO products(id,name,brief,description,price,image_url,thumbnail_url,merchant_id,lifecycle_status,
                     business_version)
SELECT id,name,brief,description,price,CONCAT('https://picsum.photos/seed/',image_seed,'/800/500'),
  CONCAT('https://picsum.photos/seed/',image_seed,'/160/100'),@demo_merchant_id,'ACTIVE',1
FROM demo_products WHERE NOT @seeded;

INSERT INTO product_translations(product_id,locale,name,brief,description)
SELECT id,'zh-CN',zh_name,zh_brief,zh_description FROM demo_products WHERE NOT @seeded;

INSERT INTO catalog_cutover_state(cutover_key,status,source_version,product_count,translation_count,
                                  product_fingerprint,translation_fingerprint,completed_at)
SELECT 'catalog-demo-v1','COMPLETE',0,COUNT(*),COUNT(*),0,0,CURRENT_TIMESTAMP(6)
FROM demo_products HAVING NOT @seeded;
COMMIT;
DO RELEASE_LOCK('commerceflow.catalog.bootstrap');
SELECT IF(@seeded,'Catalog demo already initialized','Catalog demo initialized');

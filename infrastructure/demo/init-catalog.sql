BEGIN;
SELECT pg_advisory_xact_lock(hashtext('commerceflow.catalog-demo-v1'));
SELECT EXISTS (SELECT 1 FROM catalog_cutover_state WHERE cutover_key='catalog-demo-v1' AND status='COMPLETE') AS seeded \gset
\if :seeded
\else
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM catalog_cutover_state)
     OR EXISTS (SELECT 1 FROM products)
     OR EXISTS (SELECT 1 FROM product_translations)
     OR EXISTS (SELECT 1 FROM catalog_event_outbox) THEN
    RAISE EXCEPTION 'Demo initialization requires an empty database';
  END IF;
END $$;
\ir products.sql
INSERT INTO products(id,name,brief,description,price,image_url,thumbnail_url,merchant_id,lifecycle_status,
                     business_version)
SELECT id,name,brief,description,price,CONCAT('https://picsum.photos/seed/',image_seed,'/800/500'),
  CONCAT('https://picsum.photos/seed/',image_seed,'/160/100'),:merchant_id,'ACTIVE',1
FROM demo_products;

INSERT INTO product_translations(product_id,locale,name,brief,description)
SELECT id,'zh-CN',zh_name,zh_brief,zh_description FROM demo_products;

INSERT INTO catalog_cutover_state(cutover_key,status,source_version,product_count,translation_count,
                                  product_fingerprint,translation_fingerprint,completed_at)
SELECT 'catalog-demo-v1','COMPLETE',0,COUNT(*),COUNT(*),0,0,CURRENT_TIMESTAMP(6)
FROM demo_products;
-- Bootstrap inserts bypass the catalog Outbox and need a fresh search projection.
UPDATE catalog_search_state SET rebuild_requested=TRUE WHERE singleton_id=1;
SELECT setval(pg_get_serial_sequence('products','id'), MAX(id)) FROM products;
\endif
COMMIT;

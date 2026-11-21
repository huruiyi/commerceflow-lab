START TRANSACTION;
CREATE TEMPORARY TABLE demo_guard (verified INT PRIMARY KEY);
INSERT INTO demo_guard VALUES (0);
INSERT INTO demo_guard VALUES (COALESCE(GET_LOCK('commerceflow.inventory-shadow-migration',30),0));

SET @seeded = EXISTS (
  SELECT 1 FROM inventory_migration_state WHERE migration_key='inventory-demo-v1' AND status='READY'
);
SELECT COUNT(*) INTO @products FROM product_inventory FOR UPDATE;
INSERT INTO demo_guard
SELECT IF(@seeded OR (
  @products=0
  AND NOT EXISTS (SELECT 1 FROM inventory_migration_state)
  AND NOT EXISTS (SELECT 1 FROM inventory_reservations)
  AND NOT EXISTS (SELECT 1 FROM inventory_movements)
  AND NOT EXISTS (SELECT 1 FROM inventory_command_inbox)
  AND NOT EXISTS (SELECT 1 FROM inventory_catalog_event_inbox)
  AND NOT EXISTS (SELECT 1 FROM inventory_outbound_documents)
  AND NOT EXISTS (SELECT 1 FROM inventory_delivered_reconciliation)
),2,0);

INSERT INTO product_inventory(product_id,merchant_id,product_name,thumbnail_url,unit_price,
                              catalog_lifecycle_status,catalog_business_version,stock_available,
                              stock_reserved,stock_sold)
SELECT id,@demo_merchant_id,name,CONCAT('https://picsum.photos/seed/',image_seed,'/160/100'),
  price,'ACTIVE',1,100,0,0
FROM demo_products WHERE NOT @seeded;

INSERT INTO inventory_warehouse_stock(product_id,warehouse_id,stock_available,stock_reserved,stock_sold)
SELECT p.product_id,1,p.stock_available,p.stock_reserved,p.stock_sold
FROM product_inventory p JOIN demo_products d ON d.id=p.product_id
WHERE NOT @seeded;

INSERT INTO inventory_migration_state(migration_key,status,source_version,generation,source_fingerprint,completed_at)
SELECT 'inventory-demo-v1','READY',0,1,'catalog-demo-v1',CURRENT_TIMESTAMP(6) WHERE NOT @seeded;
COMMIT;
DO RELEASE_LOCK('commerceflow.inventory-shadow-migration');
SELECT IF(@seeded,'Inventory demo already initialized','Inventory demo initialized');

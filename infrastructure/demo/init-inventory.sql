BEGIN;
SELECT pg_advisory_xact_lock(hashtext('commerceflow.inventory-demo-v1'));
SELECT EXISTS (SELECT 1 FROM inventory_migration_state WHERE migration_key='inventory-demo-v1' AND status='READY') AS seeded \gset
\if :seeded
\else
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM inventory_migration_state)
     OR EXISTS (SELECT 1 FROM product_inventory)
     OR EXISTS (SELECT 1 FROM inventory_reservations)
     OR EXISTS (SELECT 1 FROM inventory_movements)
     OR EXISTS (SELECT 1 FROM inventory_command_inbox)
     OR EXISTS (SELECT 1 FROM inventory_catalog_event_inbox)
     OR EXISTS (SELECT 1 FROM inventory_outbound_documents)
     OR EXISTS (SELECT 1 FROM inventory_delivered_reconciliation) THEN
    RAISE EXCEPTION 'Demo initialization requires an empty database';
  END IF;
END $$;
\ir products.sql
INSERT INTO product_inventory(product_id,merchant_id,product_name,thumbnail_url,unit_price,
                              catalog_lifecycle_status,catalog_business_version,stock_available,
                              stock_reserved,stock_sold)
SELECT id,:merchant_id,name,CONCAT('https://picsum.photos/seed/',image_seed,'/160/100'),
  price,'ACTIVE',1,100,0,0
FROM demo_products;

INSERT INTO inventory_warehouse_stock (product_id,warehouse_id,stock_available,stock_reserved,stock_sold)
SELECT product_id,1,stock_available,stock_reserved,stock_sold
FROM product_inventory;

INSERT INTO inventory_migration_state(migration_key,status,source_version,generation,source_fingerprint,completed_at)
SELECT 'inventory-demo-v1','READY',0,1,'catalog-demo-v1',CURRENT_TIMESTAMP(6);
\endif
COMMIT;

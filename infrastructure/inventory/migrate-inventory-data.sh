#!/bin/sh
set -eu

MIGRATION_KEY="inventory-v34-shadow-snapshot"
SOURCE_SCHEMA="commerceflow"
TARGET_SCHEMA="inventory"
SOURCE_VERSION="34"
EXPORT_FILE="/tmp/inventory-shadow.sql"

source_query() {
  MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot -N -B "$SOURCE_SCHEMA" -e "$1"
}

target_query() {
  MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h inventory-db -uroot -N -B "$TARGET_SCHEMA" -e "$1"
}

fingerprint_query() {
  cat <<'SQL'
SELECT CONCAT('product_inventory|',COUNT(*),'|',COALESCE(SUM(stock_available),0),'|',COALESCE(SUM(stock_reserved),0),'|',COALESCE(SUM(stock_sold),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,COALESCE(merchant_id,''),product_name,thumbnail_url,unit_price,catalog_lifecycle_status,catalog_business_version,stock_available,stock_reserved,stock_sold,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM product_inventory
UNION ALL
SELECT CONCAT('inventory_reservations|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_id,product_id,quantity,status,version,DATE_FORMAT(reserved_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(committed_at,'%Y-%m-%d %H:%i:%s.%f'),''),COALESCE(DATE_FORMAT(released_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_reservations
UNION ALL
SELECT CONCAT('inventory_movements|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,COALESCE(order_id,''),product_id,movement_type,quantity,available_after,reserved_after,sold_after,detail,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM inventory_movements
UNION ALL
SELECT CONCAT('inventory_command_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',command_id,command_type,order_id,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_command_inbox
UNION ALL
SELECT CONCAT('inventory_catalog_event_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',event_id,product_id,business_version,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_catalog_event_inbox;
SQL
}

actual_version="$(source_query "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1")"
if [ "$actual_version" -lt "$SOURCE_VERSION" ]; then
  echo "commerce schema version ${actual_version} is below required V${SOURCE_VERSION}" >&2
  exit 1
fi

open_facts="$(source_query "SELECT (SELECT COUNT(*) FROM inventory_command_inbox WHERE status<>'SUCCEEDED')+(SELECT COUNT(*) FROM inventory_catalog_event_inbox WHERE status='PROCESSING')+(SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE status<>'ACKNOWLEDGED')+(SELECT COUNT(*) FROM shop_orders WHERE inventory_status IN ('WAITING_RESERVATION','WAITING_COMMIT','REFUND_PENDING','RECONCILIATION_REQUIRED'))+(SELECT COUNT(*) FROM refund_records WHERE inventory_status IN ('WAITING','RECONCILIATION_REQUIRED'))")"
if [ "$open_facts" -ne 0 ]; then
  echo "inventory source has ${open_facts} unfinished facts; snapshot refused" >&2
  exit 1
fi

status="$(target_query "SELECT COALESCE(MAX(status),'') FROM inventory_migration_state WHERE migration_key='${MIGRATION_KEY}'")"
if [ "$status" = "CUTOVER_COMPLETE" ]; then
  echo "inventory cutover is complete; shadow migration is permanently disabled" >&2
  exit 1
fi

source_fingerprint="$(source_query "$(fingerprint_query)")"
generation="$(target_query "SELECT COALESCE(MAX(generation),0)+1 FROM inventory_migration_state WHERE migration_key='${MIGRATION_KEY}'")"
escaped_fingerprint="$(printf '%s' "$source_fingerprint" | tr '\n' ';' | sed "s/'/''/g")"
target_query "INSERT INTO inventory_migration_state(migration_key,status,source_version,generation,source_fingerprint) VALUES('${MIGRATION_KEY}','RUNNING',${SOURCE_VERSION},${generation},'${escaped_fingerprint}') ON DUPLICATE KEY UPDATE status='RUNNING',source_version=VALUES(source_version),generation=VALUES(generation),source_fingerprint=VALUES(source_fingerprint),completed_at=NULL"

MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysqldump -h commerce-db -uroot \
  --default-character-set=utf8mb4 --single-transaction --no-create-info --skip-triggers \
  --skip-add-locks --skip-disable-keys --compact "$SOURCE_SCHEMA" \
  product_inventory inventory_reservations inventory_movements \
  inventory_command_inbox inventory_catalog_event_inbox > "$EXPORT_FILE"

target_query "SET FOREIGN_KEY_CHECKS=0; DELETE FROM inventory_movements; DELETE FROM inventory_reservations; DELETE FROM inventory_command_inbox; DELETE FROM inventory_catalog_event_inbox; DELETE FROM product_inventory; SET FOREIGN_KEY_CHECKS=1;"
if ! MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h inventory-db -uroot \
  --default-character-set=utf8mb4 "$TARGET_SCHEMA" < "$EXPORT_FILE"; then
  target_query "UPDATE inventory_migration_state SET status='FAILED' WHERE migration_key='${MIGRATION_KEY}'"
  echo "inventory snapshot import failed" >&2
  exit 1
fi

target_fingerprint="$(target_query "$(fingerprint_query)")"
if [ "$source_fingerprint" != "$target_fingerprint" ]; then
  target_query "UPDATE inventory_migration_state SET status='FAILED' WHERE migration_key='${MIGRATION_KEY}'"
  echo "inventory snapshot fingerprint mismatch" >&2
  exit 1
fi

target_query "UPDATE inventory_migration_state SET status='READY',completed_at=CURRENT_TIMESTAMP(6) WHERE migration_key='${MIGRATION_KEY}'"
echo "inventory shadow snapshot READY generation=${generation}"
printf '%s\n' "$target_fingerprint"

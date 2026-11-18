#!/bin/sh
set -eu

MIGRATION_KEY="inventory-v34-shadow-snapshot"
SOURCE_SCHEMA="commerceflow"
TARGET_SCHEMA="inventory"
SOURCE_VERSION="34"

source_query() {
  MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot -N -B "$SOURCE_SCHEMA" -e "$1"
}

target_query() {
  MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h inventory-db -uroot -N -B "$TARGET_SCHEMA" -e "$1"
}

assert_empty_target() {
  state="$(target_query "
    SELECT CASE
      WHEN EXISTS (SELECT 1 FROM inventory_migration_state WHERE status='CUTOVER_COMPLETE') THEN 'CUTOVER_COMPLETE'
      WHEN EXISTS (SELECT 1 FROM product_inventory)
        OR EXISTS (SELECT 1 FROM inventory_reservations)
        OR EXISTS (SELECT 1 FROM inventory_movements)
        OR EXISTS (SELECT 1 FROM inventory_command_inbox)
        OR EXISTS (SELECT 1 FROM inventory_catalog_event_inbox) THEN 'NONEMPTY'
      ELSE 'EMPTY'
    END;
  ")"
  case "$state" in
    EMPTY) ;;
    CUTOVER_COMPLETE) echo 'Inventory cutover is complete; snapshot import refused' >&2; exit 1 ;;
    NONEMPTY) echo 'Inventory target already contains data; snapshot import refused' >&2; exit 1 ;;
    *) echo 'Unable to verify inventory target; snapshot import refused' >&2; exit 1 ;;
  esac
}

fingerprint_query() {
  cat <<'SQL'
SELECT GROUP_CONCAT(fingerprint ORDER BY fingerprint SEPARATOR ';') FROM (
SELECT CONCAT('product_inventory|',COUNT(*),'|',COALESCE(SUM(stock_available),0),'|',COALESCE(SUM(stock_reserved),0),'|',COALESCE(SUM(stock_sold),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,COALESCE(merchant_id,''),product_name,thumbnail_url,unit_price,catalog_lifecycle_status,catalog_business_version,stock_available,stock_reserved,stock_sold,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) AS fingerprint FROM product_inventory
UNION ALL
SELECT CONCAT('inventory_reservations|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_id,product_id,quantity,status,version,DATE_FORMAT(reserved_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(committed_at,'%Y-%m-%d %H:%i:%s.%f'),''),COALESCE(DATE_FORMAT(released_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_reservations
UNION ALL
SELECT CONCAT('inventory_movements|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,COALESCE(order_id,''),product_id,movement_type,quantity,available_after,reserved_after,sold_after,detail,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM inventory_movements
UNION ALL
SELECT CONCAT('inventory_command_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',command_id,command_type,order_id,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_command_inbox
UNION ALL
SELECT CONCAT('inventory_catalog_event_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',event_id,product_id,business_version,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_catalog_event_inbox
) AS inventory_fingerprints
SQL
}

assert_empty_target
case "${1:-}" in
  --check-target) exit 0 ;;
  '') ;;
  *) echo 'Usage: migrate-inventory-data.sh [--check-target]' >&2; exit 1 ;;
esac

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

fingerprint_sql="$(fingerprint_query)"
source_fingerprint="$(source_query "SET time_zone='+00:00'; $fingerprint_sql;")"
case "$source_fingerprint" in
  ''|*[!a-z0-9_\|\;-]*) echo 'Invalid source fingerprint; snapshot import refused' >&2; exit 1 ;;
esac

work_dir="$(mktemp -d)"
export_file="$work_dir/snapshot.sql"
import_file="$work_dir/import.sql"
trap 'rm -f "$export_file" "$import_file"; rmdir "$work_dir"' EXIT

MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysqldump -h commerce-db -uroot \
  --default-character-set=utf8mb4 --single-transaction --no-create-info --complete-insert --skip-triggers --no-tablespaces \
  --skip-add-locks --skip-disable-keys --set-gtid-purged=OFF --compact "$SOURCE_SCHEMA" \
  product_inventory inventory_reservations inventory_movements \
  inventory_command_inbox inventory_catalog_event_inbox > "$export_file"

# Keep the guard, imports and marker on one target connection. A duplicate guard key aborts mysql's batch.
cat > "$import_file" <<'SQL'
SET time_zone='+00:00';
CREATE TEMPORARY TABLE migration_guard (assertion VARCHAR(96) PRIMARY KEY);
INSERT INTO migration_guard VALUES ('migration lock unavailable'), ('inventory target changed before import'),
  ('inventory cutover completed before import'), ('inventory snapshot fingerprint mismatch');
INSERT INTO migration_guard VALUES (IF(GET_LOCK('commerceflow.inventory-shadow-migration',10)=1,
  'migration lock acquired','migration lock unavailable'));
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT COUNT(*) INTO @cutover FROM inventory_migration_state WHERE status='CUTOVER_COMPLETE' FOR UPDATE;
INSERT INTO migration_guard VALUES (IF(@cutover=0,'target not cut over','inventory cutover completed before import'));
SELECT COUNT(*) INTO @stock FROM product_inventory FOR UPDATE;
SELECT COUNT(*) INTO @reservations FROM inventory_reservations FOR UPDATE;
SELECT COUNT(*) INTO @movements FROM inventory_movements FOR UPDATE;
SELECT COUNT(*) INTO @commands FROM inventory_command_inbox FOR UPDATE;
SELECT COUNT(*) INTO @catalog_events FROM inventory_catalog_event_inbox FOR UPDATE;
INSERT INTO migration_guard VALUES (IF(@stock+@reservations+@movements+@commands+@catalog_events=0,
  'target empty','inventory target changed before import'));
SQL
cat "$export_file" >> "$import_file"
cat >> "$import_file" <<SQL
SELECT ($fingerprint_sql) INTO @target_fingerprint;
INSERT INTO migration_guard VALUES (IF(@target_fingerprint='$source_fingerprint',
  'snapshot matches','inventory snapshot fingerprint mismatch'));
INSERT INTO inventory_migration_state(migration_key,status,source_version,generation,source_fingerprint,completed_at)
VALUES('$MIGRATION_KEY','READY',$SOURCE_VERSION,1,'$source_fingerprint',CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status='READY',source_version=$SOURCE_VERSION,generation=generation+1,
  source_fingerprint='$source_fingerprint',completed_at=CURRENT_TIMESTAMP(6);
COMMIT;
SELECT CONCAT('inventory shadow snapshot READY generation=',generation) FROM inventory_migration_state
WHERE migration_key='$MIGRATION_KEY';
DO RELEASE_LOCK('commerceflow.inventory-shadow-migration');
SQL
if ! MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h inventory-db -uroot --batch --skip-column-names \
  --default-character-set=utf8mb4 "$TARGET_SCHEMA" < "$import_file"; then
  echo 'Inventory snapshot import refused or rolled back' >&2
  exit 1
fi
printf '%s\n' "$source_fingerprint"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
DATABASE_USER="${DATABASE_USER:-commerce_legacy_audit}"
DATABASE_PASSWORD="${COMMERCE_LEGACY_AUDIT_PASSWORD:-local-legacy-audit}"

fail() {
  echo "Inventory admission audit failed: $*" >&2
  exit 1
}

sql() {
  docker compose exec -T -e "MYSQL_PWD=$DATABASE_PASSWORD" commerce-db mysql --batch --skip-column-names \
    --user="$DATABASE_USER" --database=commerceflow --execute="$1"
}

assert_scalar() {
  local label="$1" query="$2" expected="$3" actual
  actual="$(sql "$query")"
  [[ "$actual" == "$expected" ]] || fail "$label expected '$expected' but was '$actual'"
  printf '  PASS %s = %s\n' "$label" "$actual"
}

cd "$PROJECT_ROOT"
docker compose up -d --wait commerce-db >/dev/null

assert_scalar 'Flyway schema version' \
  "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;" 34
assert_scalar 'inventory boundary table count' \
  "SELECT COUNT(*) FROM commerce_verification.schema_tables WHERE table_schema='commerceflow' AND table_name IN ('product_inventory','inventory_reservations','inventory_movements','inventory_command_inbox','inventory_catalog_event_inbox','inventory_command_delivery_outbox','inventory_command_result_inbox');" 7
assert_scalar 'cross-boundary inventory foreign keys' \
  "SELECT COUNT(*) FROM commerce_verification.schema_keys WHERE table_schema='commerceflow' AND table_name IN ('product_inventory','inventory_reservations','inventory_movements','inventory_command_inbox','inventory_catalog_event_inbox') AND referenced_table_name IS NOT NULL AND referenced_table_name NOT IN ('product_inventory','inventory_reservations','inventory_movements','inventory_command_inbox','inventory_catalog_event_inbox');" 0
assert_scalar 'negative inventory balances' \
  'SELECT COUNT(*) FROM product_inventory WHERE stock_available<0 OR stock_reserved<0 OR stock_sold<0;' 0
assert_scalar 'reserved balance mismatches' \
  "SELECT COUNT(*) FROM product_inventory p LEFT JOIN (SELECT product_id,SUM(quantity) quantity FROM inventory_reservations WHERE status='RESERVED' GROUP BY product_id) r ON r.product_id=p.product_id WHERE p.stock_reserved<>COALESCE(r.quantity,0);" 0
assert_scalar 'sold balance mismatches' \
  "SELECT COUNT(*) FROM product_inventory p LEFT JOIN (SELECT product_id,SUM(quantity) quantity FROM inventory_reservations WHERE status='COMMITTED' GROUP BY product_id) r ON r.product_id=p.product_id WHERE p.stock_sold<>COALESCE(r.quantity,0);" 0
assert_scalar 'unfinished inventory commands' \
  "SELECT COUNT(*) FROM inventory_command_inbox WHERE status<>'SUCCEEDED';" 0
assert_scalar 'unfinished catalog events' \
  "SELECT COUNT(*) FROM inventory_catalog_event_inbox WHERE status='PROCESSING';" 0
assert_scalar 'unacknowledged command deliveries' \
  "SELECT COUNT(*) FROM inventory_command_delivery_outbox WHERE status<>'ACKNOWLEDGED';" 0
assert_scalar 'delivery/result pairing mismatches' \
  "SELECT COUNT(*) FROM inventory_command_delivery_outbox d LEFT JOIN inventory_command_result_inbox r ON r.command_id=d.command_id WHERE d.status='ACKNOWLEDGED' AND r.command_id IS NULL;" 0
assert_scalar 'orders awaiting inventory recovery' \
  "SELECT COUNT(*) FROM shop_orders WHERE inventory_status IN ('WAITING_RESERVATION','WAITING_COMMIT','REFUND_PENDING','RECONCILIATION_REQUIRED');" 0
assert_scalar 'refunds awaiting inventory recovery' \
  "SELECT COUNT(*) FROM refund_records WHERE inventory_status IN ('WAITING','RECONCILIATION_REQUIRED');" 0

mapfile -t implementations < <(grep -RIl 'implements InventoryCommandPort {' \
  services/commerce-service/src/main/java || true)
[[ "${#implementations[@]}" == 1 && "${implementations[0]}" == *InventoryCommandGateway.java ]] || \
  fail "expected only InventoryCommandGateway to implement InventoryCommandPort: ${implementations[*]}"
if grep -REni 'INVENTORY_MODE|inventory\.mode|InventoryLocal|InventoryCompare' \
    services/commerce-service/src/main compose.yaml >/dev/null 2>&1; then
  fail 'inventory dual-mode or local fallback marker found'
fi
echo '  PASS one InventoryCommandPort implementation and no LOCAL/COMPARE mode'

echo 'Inventory migration baseline (repeat unchanged after copy and final catch-up):'
sql "
SELECT CONCAT('product_inventory|',COUNT(*),'|',COALESCE(SUM(stock_available),0),'|',COALESCE(SUM(stock_reserved),0),'|',COALESCE(SUM(stock_sold),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,COALESCE(merchant_id,''),product_name,thumbnail_url,unit_price,catalog_lifecycle_status,catalog_business_version,stock_available,stock_reserved,stock_sold,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM product_inventory
UNION ALL
SELECT CONCAT('inventory_reservations|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_id,product_id,quantity,status,version,DATE_FORMAT(reserved_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(committed_at,'%Y-%m-%d %H:%i:%s.%f'),''),COALESCE(DATE_FORMAT(released_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_reservations
UNION ALL
SELECT CONCAT('inventory_movements|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,COALESCE(order_id,''),product_id,movement_type,quantity,available_after,reserved_after,sold_after,detail,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM inventory_movements
UNION ALL
SELECT CONCAT('inventory_command_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',command_id,command_type,order_id,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_command_inbox
UNION ALL
SELECT CONCAT('inventory_catalog_event_inbox|',COUNT(*),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',event_id,product_id,business_version,payload_fingerprint,status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(processed_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)) FROM inventory_catalog_event_inbox;"

echo 'Inventory-service CREATE admission passed. CUTOVER remains denied until the independent runtime drills pass.'

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
DATABASE_USER="${DATABASE_USER:-commerceflow}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-commerceflow-password}"

fail() {
  echo "Order-service admission audit failed: $*" >&2
  exit 1
}

sql() {
  docker compose exec -T commerce-db mysql -N -B "-u$DATABASE_USER" \
    "-p$DATABASE_PASSWORD" commerceflow -e "$1" 2>/dev/null
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
assert_scalar 'order-owned table count' \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events');" 4
assert_scalar 'order outbound cross-boundary foreign keys frozen' \
  "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='commerceflow' AND table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events') AND referenced_table_name IS NOT NULL AND referenced_table_name NOT IN ('shop_orders','order_items','order_shipping_addresses','order_events');" 4
assert_scalar 'order inbound cross-boundary foreign keys frozen' \
  "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema='commerceflow' AND referenced_table_name IN ('shop_orders','order_items','order_shipping_addresses','order_events') AND table_name NOT IN ('shop_orders','order_items','order_shipping_addresses','order_events');" 5
assert_scalar 'duplicate order numbers' \
  'SELECT COUNT(*) FROM (SELECT order_number FROM shop_orders GROUP BY order_number HAVING COUNT(*)>1) d;' 0
assert_scalar 'orders without items' \
  'SELECT COUNT(*) FROM shop_orders o LEFT JOIN order_items i ON i.order_id=o.id WHERE i.id IS NULL;' 0
assert_scalar 'orders without address snapshots' \
  'SELECT COUNT(*) FROM shop_orders o LEFT JOIN order_shipping_addresses a ON a.order_id=o.id WHERE a.order_id IS NULL;' 0
assert_scalar 'orders without events' \
  'SELECT COUNT(*) FROM shop_orders o LEFT JOIN order_events e ON e.order_id=o.id WHERE e.id IS NULL;' 0
assert_scalar 'order total mismatches' \
  'SELECT COUNT(*) FROM shop_orders o JOIN (SELECT order_id,SUM(subtotal) total FROM order_items GROUP BY order_id) i ON i.order_id=o.id WHERE o.total_amount<>i.total;' 0
assert_scalar 'invalid order statuses' \
  "SELECT COUNT(*) FROM shop_orders WHERE status NOT IN ('PENDING_PAYMENT','CANCELLING','CANCELLED','PAID','REFUNDED','CONFIRMED','SHIPPED','RECEIVED','COMPLETED');" 0
assert_scalar 'invalid inventory projections' \
  "SELECT COUNT(*) FROM shop_orders WHERE inventory_status NOT IN ('WAITING_RESERVATION','RESERVED','WAITING_COMMIT','COMMITTED','RELEASED','REFUND_PENDING','REFUNDED','RECONCILIATION_REQUIRED');" 0
assert_scalar 'orders requiring workflow recovery' \
  "SELECT COUNT(*) FROM shop_orders WHERE status IN ('PENDING_PAYMENT','CANCELLING') OR inventory_status IN ('WAITING_RESERVATION','WAITING_COMMIT','REFUND_PENDING','RECONCILIATION_REQUIRED');" 0
for table in order_items order_shipping_addresses order_events payment_records refund_records shipment_records; do
  assert_scalar "$table orphan rows" \
    "SELECT COUNT(*) FROM $table child LEFT JOIN shop_orders parent ON parent.id=child.order_id WHERE parent.id IS NULL;" 0
done

mapfile -t external_consumers < <(grep -RIl \
  'import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;' \
  services/commerce-service/src/main/java | grep -v '/ordering/' || true)
[[ "${#external_consumers[@]}" == 7 ]] || \
  fail "expected seven frozen external ShopOrderMapper consumers: ${external_consumers[*]}"
if grep -REn 'org\.apache\.seata|io\.seata|dtm-labs|GlobalTransactional|TwoPhaseBusinessAction' \
    services/commerce-service/pom.xml services/commerce-service/src/main/java >/dev/null 2>&1; then
  fail 'global transaction coordinator found'
fi
echo '  PASS seven external ShopOrderMapper consumers frozen; Seata/DTM/XA coordinator absent'

echo 'Order ownership baseline:'
sql "
SELECT CONCAT('orders|',COUNT(*),'|',COALESCE(SUM(total_amount),0),'|',COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,order_number,customer_id,status,inventory_status,total_amount,currency,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)) FROM shop_orders
UNION ALL
SELECT CONCAT('items|',COUNT(*),'|',COALESCE(SUM(quantity),0),'|',COALESCE(SUM(subtotal),0)) FROM order_items
UNION ALL
SELECT CONCAT('addresses|',COUNT(*)) FROM order_shipping_addresses
UNION ALL
SELECT CONCAT('events|',COUNT(*)) FROM order_events;"

echo 'Order-service admission audit passed: data is consistent; CREATE remains denied until nine cross-boundary foreign keys and seven external ShopOrderMapper consumers are removed behind versioned ports.'

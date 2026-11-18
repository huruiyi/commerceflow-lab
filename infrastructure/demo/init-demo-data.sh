#!/bin/sh
set -eu

# Commerce Flyway creates the default learning merchant with ID 5 in a fresh environment.
merchant_id=${DEMO_MERCHANT_ID:-5}
case "$merchant_id" in
  ''|0*|*[!0-9]*) echo 'DEMO_MERCHANT_ID must be a positive integer' >&2; exit 1 ;;
esac

for sql in /demo/products.sql /demo/init-inventory.sql /demo/init-catalog.sql; do
  if [ ! -r "$sql" ]; then
    echo "Cannot read demo SQL: $sql" >&2
    exit 1
  fi
done

catalog() {
  MYSQL_PWD="$CATALOG_PASSWORD" mysql -h catalog-db -u "$CATALOG_USERNAME" \
    --default-character-set=utf8mb4 -N -B catalog "$@"
}

inventory() {
  MYSQL_PWD="$INVENTORY_PASSWORD" mysql -h inventory-db -u "$INVENTORY_USERNAME" \
    --default-character-set=utf8mb4 -N -B inventory "$@"
}

catalog_ready="$(catalog -e "
  SELECT EXISTS(SELECT 1 FROM catalog_cutover_state WHERE cutover_key='catalog-demo-v1' AND status='COMPLETE')
    OR (NOT EXISTS (SELECT 1 FROM catalog_cutover_state) AND NOT EXISTS (SELECT 1 FROM products)
      AND NOT EXISTS (SELECT 1 FROM product_translations) AND NOT EXISTS (SELECT 1 FROM catalog_event_outbox));
")"
inventory_ready="$(inventory -e "
  SELECT EXISTS(SELECT 1 FROM inventory_migration_state WHERE migration_key='inventory-demo-v1' AND status='READY')
    OR (NOT EXISTS (SELECT 1 FROM inventory_migration_state) AND NOT EXISTS (SELECT 1 FROM product_inventory)
      AND NOT EXISTS (SELECT 1 FROM inventory_reservations) AND NOT EXISTS (SELECT 1 FROM inventory_movements)
      AND NOT EXISTS (SELECT 1 FROM inventory_command_inbox) AND NOT EXISTS (SELECT 1 FROM inventory_catalog_event_inbox)
      AND NOT EXISTS (SELECT 1 FROM inventory_outbound_documents)
      AND NOT EXISTS (SELECT 1 FROM inventory_delivered_reconciliation));
")"
if [ "$catalog_ready" != 1 ] || [ "$inventory_ready" != 1 ]; then
  echo 'Demo initialization requires empty databases or this demo initialization marker' >&2
  exit 1
fi

seed() {
  printf 'SET @demo_merchant_id = %s;\n' "$merchant_id"
  cat /demo/products.sql "$1"
}

# Create sellable stock before catalog products become visible.
seed /demo/init-inventory.sql | inventory
seed /demo/init-catalog.sql | catalog

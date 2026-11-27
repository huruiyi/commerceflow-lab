#!/bin/sh
set -eu

merchant_id=${DEMO_MERCHANT_ID:-5}
case "$merchant_id" in
  ''|0*|*[!0-9]*) echo 'DEMO_MERCHANT_ID must be a positive integer' >&2; exit 1 ;;
esac

# Initialize stock before publishing the catalog products.
PGPASSWORD="$INVENTORY_PASSWORD" psql -Xq -v ON_ERROR_STOP=1 -v merchant_id="$merchant_id" \
  -h inventory-db-postgresql -U inventory_runtime -d inventory -f /demo/init-inventory.sql
PGOPTIONS="-c search_path=catalog" PGPASSWORD="$CATALOG_PASSWORD" psql -Xq \
  -v ON_ERROR_STOP=1 -v merchant_id="$merchant_id" \
  -h catalog-db-postgresql -U catalog_runtime -d catalog -f /demo/init-catalog.sql

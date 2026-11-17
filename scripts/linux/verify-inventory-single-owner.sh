#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker
BASE_URL="${1:-http://localhost:18081}"
MAX_WAIT_SECONDS="${2:-120}"
temp_dir="$(new_temp_dir)"
cookie="$temp_dir/merchant.cookie"
increased=0
product_id=''
csrf_header=''
csrf_token=''

wait_healthy() {
  local container="$1" deadline=$((SECONDS + MAX_WAIT_SECONDS))
  while ((SECONDS < deadline)); do
    [[ "$(docker inspect "$container" --format '{{.State.Health.Status}}' 2>/dev/null || true)" == healthy ]] && return
    sleep 2
  done
  echo "$container did not become healthy" >&2
  return 1
}

cleanup() {
  local status=$?
  set +e
  compose start inventory-service >/dev/null 2>&1
  wait_healthy fairy-commerceflow-inventory-service >/dev/null 2>&1
  if ((increased == 1)); then
    json_request POST "$BASE_URL/api/merchant/inventory/$product_id/adjustments" "$cookie" \
      "$csrf_header" "$csrf_token" '{"delta":-1,"reason":"Inventory single-owner acceptance cleanup"}' >/dev/null
  fi
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
compose up -d --wait commerce-service inventory-service >/dev/null
commerce_environment="$(docker inspect fairy-commerceflow-commerce-service --format '{{range .Config.Env}}{{println .}}{{end}}')"
grep -qx 'DATABASE_USERNAME=commerce_runtime' <<<"$commerce_environment"
grep -qx 'SPRING_FLYWAY_ENABLED=false' <<<"$commerce_environment"

allowed="$(compose exec -T commerce-db mysql -N -ucommerce_runtime -pcommerce-runtime-password \
  commerceflow -e 'SELECT COUNT(*) FROM app_users;' 2>/dev/null)"
((allowed >= 1))
for table in product_inventory inventory_reservations inventory_movements \
  inventory_command_inbox inventory_catalog_event_inbox; do
  if compose exec -T commerce-db mysql -N -ucommerce_runtime -pcommerce-runtime-password \
      commerceflow -e "SELECT COUNT(*) FROM $table;" >/dev/null 2>&1; then
    echo "Runtime identity can still read $table" >&2
    exit 1
  fi
done

source_before="$(compose exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password \
  commerceflow -e 'SELECT COUNT(*) FROM inventory_movements;' 2>/dev/null)"
target_before="$(compose exec -T inventory-db mysql -N -uinventory -pinventory-password \
  inventory -e 'SELECT COUNT(*) FROM inventory_movements;' 2>/dev/null)"

IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$cookie")"
item="$(json_request GET "$BASE_URL/api/merchant/inventory" "$cookie" | jq -c '.[0]')"
product_id="$(jq -r .productId <<<"$item")"
before="$(jq -r .stockAvailable <<<"$item")"
up="$(json_request POST "$BASE_URL/api/merchant/inventory/$product_id/adjustments" "$cookie" \
  "$csrf_header" "$csrf_token" '{"delta":1,"reason":"Inventory single-owner acceptance"}')"
increased=1
[[ "$(jq -r .stockAvailable <<<"$up")" == "$((before + 1))" ]]
down="$(json_request POST "$BASE_URL/api/merchant/inventory/$product_id/adjustments" "$cookie" \
  "$csrf_header" "$csrf_token" '{"delta":-1,"reason":"Inventory single-owner acceptance restore"}')"
increased=0
[[ "$(jq -r .stockAvailable <<<"$down")" == "$before" ]]

assert_status 'unsigned inventory request rejected' 401 \
  "$(http_status GET 'http://localhost:18088/internal/v1/inventory/merchants/1/items')"
compose stop inventory-service >/dev/null
assert_status 'inventory outage is fail-closed' 503 \
  "$(http_status GET "$BASE_URL/api/merchant/inventory" "$cookie")"
compose start inventory-service >/dev/null
wait_healthy fairy-commerceflow-inventory-service
json_request GET "$BASE_URL/api/merchant/inventory" "$cookie" >/dev/null

source_after="$(compose exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password \
  commerceflow -e 'SELECT COUNT(*) FROM inventory_movements;' 2>/dev/null)"
target_after="$(compose exec -T inventory-db mysql -N -uinventory -pinventory-password \
  inventory -e 'SELECT COUNT(*) FROM inventory_movements;' 2>/dev/null)"
[[ "$source_after" == "$source_before" ]]
((target_after >= target_before + 2))

echo "Inventory single-owner acceptance passed: product=$product_id, stock=$before, source=$source_after, target=$target_after, restricted tables=5, outage=503."

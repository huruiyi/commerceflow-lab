#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "$0")" && pwd)/_verification-db.sh"
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl docker sed
BASE_URL="${1:-http://localhost:18081}"
cd "$PROJECT_ROOT"
docker compose --profile inventory-migration run --rm --no-deps inventory-data-migrator --check-target
temp_dir="$(new_temp_dir)"
cookie_jar="$temp_dir/admin.cookies"

commerce_sql() { verify_db commerce "$1" | tail -n 1; }

inventory_sql() {
  docker compose exec -T inventory-db mysql -N -uinventory -pinventory-password \
    inventory -e "$1" 2>/dev/null
}

restart_runtimes() {
  docker compose up -d --force-recreate --wait \
    inventory-service commerce-service >/dev/null
}

cleanup() {
  local status=$?
  set +e
  export INVENTORY_WRITE_FROZEN=false
  export INVENTORY_REMOTE_REHEARSAL_ENABLED=false
  export INVENTORY_ACCEPTING_TRAFFIC=true
  restart_runtimes >/dev/null 2>&1
  unset INVENTORY_WRITE_FROZEN INVENTORY_REMOTE_REHEARSAL_ENABLED INVENTORY_ACCEPTING_TRAFFIC
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
export INVENTORY_WRITE_FROZEN=true
export INVENTORY_REMOTE_REHEARSAL_ENABLED=false
export INVENTORY_ACCEPTING_TRAFFIC=false
restart_runtimes
[[ "$(docker compose exec -T commerce-service printenv INVENTORY_WRITE_FROZEN)" == true ]]
docker compose --profile inventory-migration run --rm inventory-data-migrator

command_id="$(commerce_sql "SELECT command_id FROM inventory_command_delivery_outbox WHERE status='ACKNOWLEDGED' ORDER BY created_at DESC LIMIT 1;")"
[[ -n "$command_id" ]]
stats_query="SELECT CONCAT((SELECT SUM(stock_available) FROM product_inventory),'|',(SELECT SUM(stock_reserved) FROM product_inventory),'|',(SELECT SUM(stock_sold) FROM product_inventory),'|',(SELECT COUNT(*) FROM inventory_movements),'|',(SELECT COUNT(*) FROM inventory_command_inbox));"
before="$(inventory_sql "$stats_query")"

export INVENTORY_WRITE_FROZEN=false
export INVENTORY_REMOTE_REHEARSAL_ENABLED=true
export INVENTORY_ACCEPTING_TRAFFIC=true
restart_runtimes

csrf="$(curl -fsS -c "$cookie_jar" -b "$cookie_jar" "$BASE_URL/api/auth/csrf")"
header="$(printf '%s' "$csrf" | sed -n 's/.*"headerName":"\([^"]*\)".*/\1/p')"
token="$(printf '%s' "$csrf" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
[[ -n "$header" && -n "$token" ]]
login_status="$(curl -sS -o /dev/null -w '%{http_code}' -c "$cookie_jar" -b "$cookie_jar" \
  -H "$header: $token" -H 'Content-Type: application/json' \
  --data '{"email":"admin@example.com","password":"demo-password","rememberMe":false}' \
  "$BASE_URL/api/auth/login")"
[[ "$login_status" == 200 ]]

rehearsal_url="$BASE_URL/api/admin/inventory-command-recoveries/$command_id/remote-rehearsal"
result="$(curl -fsS -X POST -c "$cookie_jar" -b "$cookie_jar" -H "$header: $token" "$rehearsal_url")"
[[ "$result" == *'"outcome":"SUCCEEDED"'* && "$result" == *"\"commandId\":\"$command_id\""* ]]
after="$(inventory_sql "$stats_query")"
[[ "$before" == "$after" ]]

docker compose stop inventory-service >/dev/null
outage="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -c "$cookie_jar" -b "$cookie_jar" \
  -H "$header: $token" "$rehearsal_url")"
[[ "$outage" == 503 ]]
docker compose start inventory-service >/dev/null
docker compose up -d --wait inventory-service >/dev/null
result="$(curl -fsS -X POST -c "$cookie_jar" -b "$cookie_jar" -H "$header: $token" "$rehearsal_url")"
[[ "$result" == *'"outcome":"SUCCEEDED"'* ]]

echo "Inventory remote rehearsal passed: freeze/catch-up, command=$command_id, idempotent=$after, outage=503, recovery=SUCCEEDED."

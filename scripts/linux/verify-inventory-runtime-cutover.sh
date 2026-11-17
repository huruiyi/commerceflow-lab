#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker openssl
BASE_URL="${1:-http://localhost:18081}"
TEMPO_URL="${2:-http://localhost:13200}"
MAX_WAIT_SECONDS="${3:-120}"
tempo_was_running="$(compose ps -q tempo 2>/dev/null || true)"
temp_dir="$(new_temp_dir)"

commerce_sql() {
  compose exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password commerceflow \
    -e "$1" 2>/dev/null
}

inventory_sql() {
  compose exec -T inventory-db mysql -N -uinventory -pinventory-password inventory \
    -e "$1" 2>/dev/null
}

wait_healthy() {
  local container="$1" deadline=$((SECONDS + MAX_WAIT_SECONDS))
  while ((SECONDS < deadline)); do
    [[ "$(docker inspect "$container" --format '{{.State.Health.Status}}' 2>/dev/null || true)" == healthy ]] && return
    sleep 2
  done
  echo "$container did not become healthy" >&2
  return 1
}

wait_delivery() {
  local order_id="$1" expected="${2:-ACKNOWLEDGED}" deadline=$((SECONDS + MAX_WAIT_SECONDS)) row=''
  while ((SECONDS < deadline)); do
    row="$(commerce_sql "SELECT CONCAT(status,'|',attempts) FROM inventory_command_delivery_outbox WHERE order_id=$order_id AND command_type='RESERVE';")"
    [[ "$row" == "$expected|"* ]] && { printf '%s' "$row"; return; }
    sleep 1
  done
  echo "Order $order_id inventory delivery did not reach $expected (actual=$row)" >&2
  return 1
}

new_order() {
  local suffix="$1" traceparent="${2:-}" cookie="$temp_dir/customer-$suffix.cookie" auth csrf_header csrf_token args
  auth="$(storefront_login "$BASE_URL" demo@example.com "$cookie")"
  IFS=$'\t' read -r csrf_header csrf_token <<<"$auth"
  json_request POST "$BASE_URL/api/cart/items" "$cookie" "$csrf_header" "$csrf_token" \
    '{"productId":1,"quantity":1,"note":"inventory-runtime-cutover"}' >/dev/null
  args=(-fsS -b "$cookie" -c "$cookie" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json')
  [[ -n "$traceparent" ]] && args+=(-H "traceparent: $traceparent")
  curl "${args[@]}" --data '{}' "$BASE_URL/api/purchase-orders/checkout" | jq -r .id
}

recreate_runtime() {
  local tracing="$1" delay="${2:-0s}"
  TRACING_EXPORT_ENABLED="$tracing" INVENTORY_RESULT_DELAY_LAB="$delay" \
    compose up -d --no-deps --force-recreate commerce-service inventory-service >/dev/null
  wait_healthy fairy-commerceflow-commerce-service
  wait_healthy fairy-commerceflow-inventory-service
}

cleanup() {
  local status=$?
  set +e
  compose start inventory-db inventory-service >/dev/null 2>&1
  wait_healthy fairy-commerceflow-inventory-db >/dev/null 2>&1
  recreate_runtime false >/dev/null 2>&1
  [[ -n "$tempo_was_running" ]] || compose stop tempo >/dev/null 2>&1
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
compose up -d --wait commerce-service inventory-service >/dev/null
docker inspect fairy-commerceflow-commerce-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -qx 'INVENTORY_WRITE_FROZEN=false'
docker inspect fairy-commerceflow-inventory-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -qx 'INVENTORY_ACCEPTING_TRAFFIC=true'

source_before="$(commerce_sql 'SELECT COUNT(*) FROM inventory_movements;')"
target_before="$(inventory_sql 'SELECT COUNT(*) FROM inventory_movements;')"
compose --profile observability up -d tempo >/dev/null
recreate_runtime true
bash "$SCRIPT_DIR/verify-order-cancellation.sh" "$BASE_URL"
bash "$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"
bash "$SCRIPT_DIR/verify-refunds.sh" "$BASE_URL"

trace_summary=''
for command_type in RESERVE COMMIT RELEASE REFUND; do
  traceparent="$(commerce_sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceparent')) FROM inventory_command_delivery_outbox WHERE command_type='$command_type' ORDER BY created_at DESC LIMIT 1;")"
  command_trace_id="$(cut -d- -f2 <<<"$traceparent")"
  deadline=$((SECONDS + MAX_WAIT_SECONDS)); services=''
  while ((SECONDS < deadline)); do
    sleep 2
    services="$(curl -fsS "$TEMPO_URL/api/traces/$command_trace_id" 2>/dev/null | \
      jq -r '[.batches[].resource.attributes[]|select(.key=="service.name")|.value.stringValue]|unique|join(",")' 2>/dev/null || true)"
    [[ ",$services," == *,commerce-service,* && ",$services," == *,inventory-service,* ]] && break
  done
  [[ ",$services," == *,commerce-service,* && ",$services," == *,inventory-service,* ]]
  trace_summary+="${trace_summary:+,}$command_type=$command_trace_id"
done

trace_id="$(openssl rand -hex 16)"
parent_id="$(openssl rand -hex 8)"
traced_order="$(new_order trace "00-$trace_id-$parent_id-01")"
wait_delivery "$traced_order" >/dev/null
deadline=$((SECONDS + MAX_WAIT_SECONDS)); services=''
while ((SECONDS < deadline)); do
  sleep 2
  services="$(curl -fsS "$TEMPO_URL/api/traces/$trace_id" 2>/dev/null | \
    jq -r '[.batches[].resource.attributes[]|select(.key=="service.name")|.value.stringValue]|unique|join(",")' 2>/dev/null || true)"
  [[ ",$services," == *,commerce-service,* && ",$services," == *,inventory-service,* ]] && break
done
[[ ",$services," == *,commerce-service,* && ",$services," == *,inventory-service,* ]]

recreate_runtime true 8s
lost_order="$(new_order lost)"
sleep 5
[[ "$(wait_delivery "$lost_order" IN_FLIGHT)" == 'IN_FLIGHT|1' ]]
[[ "$(inventory_sql "SELECT COUNT(*) FROM inventory_movements WHERE order_id=$lost_order;")" == 1 ]]
recreate_runtime true
recovered="$(wait_delivery "$lost_order")"
(( ${recovered##*|} >= 2 ))

compose stop inventory-service >/dev/null
service_order="$(new_order service)"
sleep 2
compose start inventory-service >/dev/null
wait_healthy fairy-commerceflow-inventory-service
service_recovered="$(wait_delivery "$service_order")"
(( ${service_recovered##*|} >= 2 ))

compose stop inventory-db >/dev/null
database_order="$(new_order database)"
sleep 2
compose start inventory-db >/dev/null
wait_healthy fairy-commerceflow-inventory-db
wait_healthy fairy-commerceflow-inventory-service
database_recovered="$(wait_delivery "$database_order")"
(( ${database_recovered##*|} >= 2 ))

source_after="$(commerce_sql 'SELECT COUNT(*) FROM inventory_movements;')"
target_after="$(inventory_sql 'SELECT COUNT(*) FROM inventory_movements;')"
[[ "$source_after" == "$source_before" ]]
(( target_after > target_before ))
[[ "$(commerce_sql "SELECT COUNT(DISTINCT command_type) FROM inventory_command_delivery_outbox WHERE status='ACKNOWLEDGED' AND command_type IN ('RESERVE','COMMIT','RELEASE','REFUND');")" == 4 ]]

echo "Inventory runtime cutover passed: traces=[$trace_summary], explicit-reserve=$trace_id, result-loss retry, service/database recovery, source=$source_after, target=$target_after."

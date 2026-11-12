#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl docker

BASE_URL="${1:-http://localhost:14200}"
LOGISTICS_URL="${LOGISTICS_URL:-http://localhost:18083}"
APP_ID='commerceflow-storefront'
SECRET='demo-logistics-request-secret'
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
temp_dir="$(new_temp_dir)"
customer_jar="$temp_dir/customer.cookies"
admin_jar="$temp_dir/admin.cookies"
restored=false

names=(LOGISTICS_CALLBACK_DELIVERY_ENABLED LOGISTICS_CALLBACK_DELIVERY_INITIAL_DELAY LOGISTICS_CALLBACK_DELIVERY_SCAN_INTERVAL LOGISTICS_CALLBACK_DELIVERY_RETRY_DELAY LOGISTICS_CALLBACK_DELIVERY_MAX_ATTEMPTS LOGISTICS_RECONCILIATION_ENABLED)
declare -A previous was_set
for name in "${names[@]}"; do
  if [[ -v "$name" ]]; then was_set[$name]=1; previous[$name]="${!name}"; else was_set[$name]=0; fi
done

restore_environment() {
  [[ "$restored" == true ]] && return 0
  for name in "${names[@]}"; do
    if [[ "${was_set[$name]}" == 1 ]]; then export "$name=${previous[$name]}"; else unset "$name"; fi
  done
  docker compose --project-directory "$PROJECT_ROOT" up -d --wait --force-recreate mock-logistics-service backend web >/dev/null || true
  restored=true
}
trap 'restore_environment; rm -rf -- "$temp_dir"' EXIT

signed_headers() {
  local body="${1:-}" timestamp nonce hash canonical signature
  timestamp="$(date +%s)"; nonce="$(openssl rand -hex 16)"
  hash="$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')"
  canonical="$(printf '%s\n%s\n%s\n%s' "$APP_ID" "$timestamp" "$nonce" "$hash")"
  signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$SECRET" -binary | openssl base64 -A)"
  printf '%s\t%s\t%s\t%s\n' "$APP_ID" "$timestamp" "$nonce" "$signature"
}

wait_projection() {
  local order_id="$1" status="$2" projection i
  for ((i=0; i<80; i++)); do
    projection="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id/logistics" "$customer_jar" 2>/dev/null || true)"
    [[ -n "$projection" && "$(jq -r '.status // empty' <<<"$projection")" == "$status" ]] && return 0
    sleep 0.5
  done
  echo "Projection did not reach $status" >&2; return 1
}

logistics_sql() {
  docker compose --project-directory "$PROJECT_ROOT" exec -T logistics-mysql mysql --batch --skip-column-names \
    "-u${LOGISTICS_DATABASE_USERNAME:-logistics}" "-p${LOGISTICS_DATABASE_PASSWORD:-logistics-password}" logistics_platform -e "$1" | tail -n 1 | tr -d '\r'
}

storefront_sql() {
  docker compose --project-directory "$PROJECT_ROOT" exec -T mysql mysql --batch --skip-column-names \
    "-u${DATABASE_USERNAME:-commerceflow}" "-p${DATABASE_PASSWORD:-commerceflow-password}" commerceflow -e "$1" | tail -n 1 | tr -d '\r'
}

export LOGISTICS_CALLBACK_DELIVERY_ENABLED=true LOGISTICS_CALLBACK_DELIVERY_INITIAL_DELAY=1s
export LOGISTICS_CALLBACK_DELIVERY_SCAN_INTERVAL=500ms LOGISTICS_CALLBACK_DELIVERY_RETRY_DELAY=1s
export LOGISTICS_CALLBACK_DELIVERY_MAX_ATTEMPTS=1 LOGISTICS_RECONCILIATION_ENABLED=false
docker compose --project-directory "$PROJECT_ROOT" up -d --wait --force-recreate mock-logistics-service backend web >/dev/null

"$SCRIPT_DIR/verify-merchant-fulfillment.sh" "$BASE_URL"
IFS=$'\t' read -r _ _ <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
order="$(jq -c '[.[] | select(.status=="SHIPPED" and .logistics!=null)] | sort_by(.createdAt) | reverse | .[0]' <<<"$orders")"
order_id="$(jq -r '.id // empty' <<<"$order")"; merchant_no="$(jq -r '.logistics.merchantShipmentNo // empty' <<<"$order")"
[[ -n "$order_id" && -n "$merchant_no" ]] || { echo 'Created shipment is not visible' >&2; exit 1; }

docker compose --project-directory "$PROJECT_ROOT" stop backend >/dev/null
IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
advanced="$(curl -fsS -X POST -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/advance")"
event_id="$(jq -r '.events[-1].eventId' <<<"$advanced")"
dead_state=''
for ((i=0; i<30; i++)); do dead_state="$(logistics_sql "SELECT CONCAT(id,':',status,':',attempts) FROM callback_outbox WHERE event_id='$event_id'")"; [[ "$dead_state" =~ ^[0-9]+:DEAD:1$ ]] && break; sleep 0.5; done
[[ "$dead_state" =~ ^([0-9]+):DEAD:1$ ]] || { echo "Callback did not enter DEAD: $dead_state" >&2; exit 1; }
outbox_id="${BASH_REMATCH[1]}"

docker compose --project-directory "$PROJECT_ROOT" up -d --wait backend web >/dev/null
IFS=$'\t' read -r _ _ <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
assert_status 'customer callback governance rejected' 403 "$(http_status GET "$BASE_URL/api/admin/logistics-callbacks?status=DEAD" "$customer_jar")"
dead_callbacks="$(json_request GET "$BASE_URL/api/admin/logistics-callbacks?status=DEAD" "$admin_jar")"
jq -e --argjson id "$outbox_id" 'any(.[]; .id==$id)' <<<"$dead_callbacks" >/dev/null || { echo 'Administrator cannot see DEAD callback' >&2; exit 1; }
assert_status 'empty replay reason rejected' 400 "$(http_status POST "$BASE_URL/api/admin/logistics-callbacks/$outbox_id/replay" "$admin_jar" "$csrf_header" "$csrf_token" '{"reason":""}')"

body='{"reason":"Verification: storefront recovered after callback delivery stopped"}'
replayed="$(json_request POST "$BASE_URL/api/admin/logistics-callbacks/$outbox_id/replay" "$admin_jar" "$csrf_header" "$csrf_token" "$body")"
jq -e '.status=="RETRY" or .status=="PROCESSING" or .status=="PUBLISHED"' <<<"$replayed" >/dev/null
wait_projection "$order_id" PICKED_UP
published=''
for ((i=0; i<30; i++)); do published="$(logistics_sql "SELECT status FROM callback_outbox WHERE id=$outbox_id")"; [[ "$published" == PUBLISHED ]] && break; sleep 0.5; done
[[ "$published" == PUBLISHED ]] || { echo "Replayed callback was not published: $published" >&2; exit 1; }

events="$(json_request GET "$BASE_URL/api/admin/logistics-callbacks/$outbox_id/events" "$admin_jar")"
for required in DELIVERY_FAILED MANUAL_REPLAY_REQUESTED DELIVERY_PUBLISHED; do
  jq -e --arg event "$required" 'any(.[]; .eventType==$event)' <<<"$events" >/dev/null || { echo "Audit trail is missing $required" >&2; exit 1; }
done
jq -e 'any(.[]; .eventType=="MANUAL_REPLAY_REQUESTED" and .operatedBy=="admin@example.com" and (.reason|length)>0)' <<<"$events" >/dev/null
assert_status 'duplicate replay rejected' 409 "$(http_status POST "$BASE_URL/api/admin/logistics-callbacks/$outbox_id/replay" "$admin_jar" "$csrf_header" "$csrf_token" "$body")"
[[ "$(storefront_sql "SELECT COUNT(*) FROM shipment_notifications WHERE notification_id='$event_id'")" == 1 ]] || { echo 'Callback event was not idempotent' >&2; exit 1; }

metrics="$(curl -fsS "$LOGISTICS_URL/actuator/prometheus")"
for metric in commerceflow_logistics_callback_outbox commerceflow_logistics_callback_replays_total commerceflow_logistics_callback_delivery_total; do
  grep -q "$metric" <<<"$metrics" || { echo "Prometheus output is missing $metric" >&2; exit 1; }
done

echo "Logistics callback governance verification passed: DEAD (stopped retry) -> administrator replay -> PUBLISHED (published), with RBAC, audit, idempotency and metrics (order=$(jq -r .orderNumber <<<"$order"))"
restore_environment

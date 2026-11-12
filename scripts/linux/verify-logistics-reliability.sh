#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl docker

BASE_URL="${1:-http://localhost:14200}"
LOGISTICS_URL="${2:-${LOGISTICS_URL:-http://localhost:18083}}"
APP_ID='commerceflow-storefront'
SECRET='demo-logistics-request-secret'
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
temp_dir="$(new_temp_dir)"
customer_jar="$temp_dir/customer.cookies"
restored=false

names=(LOGISTICS_CALLBACK_DELIVERY_ENABLED LOGISTICS_RECONCILIATION_ENABLED LOGISTICS_RECONCILIATION_INITIAL_DELAY LOGISTICS_RECONCILIATION_SCAN_INTERVAL LOGISTICS_RECONCILIATION_STALE_AFTER LOGISTICS_RECONCILIATION_BATCH_SIZE)
declare -A previous was_set
for name in "${names[@]}"; do
  if [[ -v "$name" ]]; then was_set[$name]=1; previous[$name]="${!name}"; else was_set[$name]=0; fi
done

restore_environment() {
  [[ "$restored" == true ]] && return 0
  for name in "${names[@]}"; do
    if [[ "${was_set[$name]}" == 1 ]]; then export "$name=${previous[$name]}"; else unset "$name"; fi
  done
  docker compose --project-directory "$PROJECT_ROOT" up -d --wait --force-recreate logistics-simulator-service commerce-service commerce-portal-angular >/dev/null || true
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

login_customer() { rm -f -- "$customer_jar"; storefront_login "$BASE_URL" demo@example.com "$customer_jar" >/dev/null; }

wait_projection() {
  local order_id="$1" status="$2" projection i
  for ((i=0; i<80; i++)); do
    projection="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id/logistics" "$customer_jar" 2>/dev/null || true)"
    [[ -n "$projection" && "$(jq -r '.status // empty' <<<"$projection")" == "$status" ]] && { printf '%s' "$projection"; return 0; }
    sleep 0.5
  done
  echo "Projection did not reach $status" >&2; return 1
}

logistics_sql() {
  docker compose --project-directory "$PROJECT_ROOT" exec -T logistics-simulator-db mysql --batch --skip-column-names \
    "-u${LOGISTICS_DATABASE_USERNAME:-logistics}" "-p${LOGISTICS_DATABASE_PASSWORD:-logistics-password}" logistics_platform -e "$1" | tail -n 1 | tr -d '\r'
}

storefront_sql() {
  docker compose --project-directory "$PROJECT_ROOT" exec -T commerce-db mysql --batch --skip-column-names \
    "-u${DATABASE_USERNAME:-commerceflow}" "-p${DATABASE_PASSWORD:-commerceflow-password}" commerceflow -e "$1" | tail -n 1 | tr -d '\r'
}

"$SCRIPT_DIR/verify-merchant-fulfillment.sh" "$BASE_URL"
login_customer
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
order="$(jq -c '[.[] | select(.status=="SHIPPED" and .logistics!=null)] | sort_by(.createdAt) | reverse | .[0]' <<<"$orders")"
order_id="$(jq -r '.id // empty' <<<"$order")"; merchant_no="$(jq -r '.logistics.merchantShipmentNo // empty' <<<"$order")"
[[ -n "$order_id" && -n "$merchant_no" ]] || { echo 'Created shipment is not visible' >&2; exit 1; }
wait_projection "$order_id" CREATED >/dev/null

docker compose --project-directory "$PROJECT_ROOT" stop commerce-service >/dev/null
IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
advanced="$(curl -fsS -X POST -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/advance")"
event_id="$(jq -r '.events[-1].eventId' <<<"$advanced")"
[[ "$(jq -r .status <<<"$advanced")" == PICKED_UP ]] || { echo 'Shipment did not advance while commerce-service was stopped' >&2; exit 1; }
retry_state=''
for ((i=0; i<20; i++)); do retry_state="$(logistics_sql "SELECT CONCAT(status,':',attempts) FROM callback_outbox WHERE event_id='$event_id'")"; [[ "$retry_state" == RETRY:* ]] && break; sleep 0.5; done
[[ "$retry_state" =~ ^RETRY:[1-9][0-9]*$ ]] || { echo "Callback did not enter RETRY: $retry_state" >&2; exit 1; }

docker compose --project-directory "$PROJECT_ROOT" up -d --wait commerce-service commerce-portal-angular >/dev/null
login_customer; wait_projection "$order_id" PICKED_UP >/dev/null
published_state="$(logistics_sql "SELECT CONCAT(status,':',attempts) FROM callback_outbox WHERE event_id='$event_id'")"
[[ "$published_state" =~ ^PUBLISHED:([2-9]|[1-9][0-9]+)$ ]] || { echo "Retried callback was not published: $published_state" >&2; exit 1; }

export LOGISTICS_CALLBACK_DELIVERY_ENABLED=false LOGISTICS_RECONCILIATION_ENABLED=true LOGISTICS_RECONCILIATION_INITIAL_DELAY=2s LOGISTICS_RECONCILIATION_SCAN_INTERVAL=1s LOGISTICS_RECONCILIATION_STALE_AFTER=0s LOGISTICS_RECONCILIATION_BATCH_SIZE=1000
docker compose --project-directory "$PROJECT_ROOT" up -d --wait --force-recreate logistics-simulator-service commerce-service commerce-portal-angular >/dev/null
login_customer
IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
lost="$(curl -fsS -X POST -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/advance")"
lost_event_id="$(jq -r '.events[-1].eventId' <<<"$lost")"
[[ "$(jq -r .status <<<"$lost")" == IN_TRANSIT ]] || { echo 'Shipment did not advance for lost callback experiment' >&2; exit 1; }
sleep 2
[[ "$(logistics_sql "SELECT status FROM callback_outbox WHERE event_id='$lost_event_id'")" == NEW ]] || { echo 'Callback was not kept undelivered' >&2; exit 1; }
wait_projection "$order_id" IN_TRANSIT >/dev/null
[[ "$(storefront_sql "SELECT processing_status FROM shipment_notifications WHERE notification_id='$lost_event_id'")" == RECONCILED ]] || { echo 'Lost callback was not recovered by reconciliation' >&2; exit 1; }

echo "Logistics reliability verification passed: callback RETRY -> PUBLISHED after commerce-service recovery; permanently lost callback recovered as RECONCILED (order=$(jq -r .orderNumber <<<"$order"))"
restore_environment

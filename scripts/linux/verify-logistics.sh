#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
LOGISTICS_URL="${2:-${LOGISTICS_URL:-http://localhost:18083}}"
APP_ID='commerceflow-storefront'
SECRET='demo-logistics-request-secret'

signed_headers() {
  local body="${1:-}" nonce="${2:-$(openssl rand -hex 16)}" timestamp hash canonical signature
  timestamp="$(date +%s)"
  hash="$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')"
  canonical="$(printf '%s\n%s\n%s\n%s' "$APP_ID" "$timestamp" "$nonce" "$hash")"
  signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$SECRET" -binary | openssl base64 -A)"
  printf '%s\t%s\t%s\t%s\n' "$APP_ID" "$timestamp" "$nonce" "$signature"
}

wait_projection() {
  local order_id="$1" expected_status="$2" expected_type="${3:-}" projection i
  for ((i=0; i<80; i++)); do
    projection="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id/logistics" "$customer_jar")"
    if [[ "$(jq -r '.status' <<<"$projection")" == "$expected_status" ]] &&
       { [[ -z "$expected_type" ]] || [[ "$(jq -r '.events[-1].eventType // empty' <<<"$projection")" == "$expected_type" ]]; }; then
      printf '%s' "$projection"
      return 0
    fi
    sleep 0.25
  done
  echo "Storefront projection did not reach $expected_status / $expected_type" >&2
  return 1
}

"$SCRIPT_DIR/verify-merchant-fulfillment.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
customer_jar="$temp_dir/customer.cookies"
storefront_login "$BASE_URL" demo@example.com "$customer_jar" >/dev/null
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
order="$(jq -c '[.[] | select(.status == "SHIPPED" and .logistics != null)][0]' <<<"$orders")"
order_id="$(jq -r '.id // empty' <<<"$order")"
merchant_no="$(jq -r '.logistics.merchantShipmentNo // empty' <<<"$order")"
[[ -n "$order_id" && -n "$merchant_no" ]] || { echo 'Customer cannot see the created shipment' >&2; exit 1; }
initial_projection="$(wait_projection "$order_id" CREATED PROGRESS)"
[[ "$(jq '.events | length' <<<"$initial_projection")" == 1 ]] || { echo 'Initial logistics projection is invalid' >&2; exit 1; }

replay_nonce="$(openssl rand -hex 16)"
IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers '' "$replay_nonce")"
query_url="$LOGISTICS_URL/api/channel/shipments?merchantShipmentNo=$merchant_no"
request_args=(-H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature")
curl -fsS "${request_args[@]}" "$query_url" >/dev/null
replay_status="$(curl -sS -o /dev/null -w '%{http_code}' "${request_args[@]}" "$query_url")"
[[ "$replay_status" == 409 ]] || { echo "Replay expected HTTP 409, received $replay_status" >&2; exit 1; }

for expected in PICKED_UP IN_TRANSIT AT_STATION OUT_FOR_DELIVERY DELIVERED; do
  IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
  remote="$(curl -fsS -X POST -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/advance")"
  [[ "$(jq -r .status <<<"$remote")" == "$expected" ]] || { echo "Remote shipment did not advance to $expected" >&2; exit 1; }
  projection="$(wait_projection "$order_id" "$expected" PROGRESS)"
  if [[ "$expected" == IN_TRANSIT ]];then
    exception_body='{"location":"Regional exception center","description":"Address label requires manual review"}'
    IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers "$exception_body")"
    exception="$(curl -fsS -X POST -H 'Content-Type: application/json' -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" --data "$exception_body" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/exception")"
    jq -e '.status=="EXCEPTION" and .events[-1].eventType=="EXCEPTION"'<<<"$exception">/dev/null||{ echo 'Shipment did not enter EXCEPTION'>&2;exit 1; }
    projection="$(wait_projection "$order_id" EXCEPTION EXCEPTION)"
    IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
    blocked_status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/advance")"
    [[ "$blocked_status" == 409 ]]||{ echo "EXCEPTION shipment advance expected HTTP 409, received $blocked_status" >&2;exit 1; }
    recover_body='{"location":"Regional sorting center","description":"Address label corrected; transportation resumed"}'
    IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers "$recover_body")"
    recovered="$(curl -fsS -X POST -H 'Content-Type: application/json' -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $signature" --data "$recover_body" "$LOGISTICS_URL/api/channel/shipments/$merchant_no/recover")"
    jq -e '.status=="IN_TRANSIT" and .events[-1].eventType=="RECOVERY"'<<<"$recovered">/dev/null||{ echo 'Shipment did not recover to IN_TRANSIT'>&2;exit 1; }
    projection="$(wait_projection "$order_id" IN_TRANSIT RECOVERY)"
  fi
done

wait_projection "$order_id" DELIVERED PROGRESS >/dev/null
final_order="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$customer_jar")"
jq -e '.logisticsDeliveredAt != null and (.logistics.events | length) == 8' <<<"$final_order" >/dev/null || { echo 'Delivered timestamp or complete logistics timeline is missing' >&2; exit 1; }

IFS=$'\t' read -r app_id timestamp nonce signature <<<"$(signed_headers)"
bad_signature="$(printf '\001%.0s' {1..32} | openssl base64 -A)"
bad_status="$(curl -sS -o /dev/null -w '%{http_code}' -H "X-App-Id: $app_id" -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Signature: $bad_signature" "$query_url")"
[[ "$bad_status" == 401 ]] || { echo "Invalid signature expected HTTP 401, received $bad_status" >&2; exit 1; }

echo "Logistics verification passed: signed create, replay=409, invalid-signature=401, EXCEPTION -> RECOVERY, CREATED -> DELIVERED, callback projection and customer timeline (order=$(jq -r .orderNumber <<<"$order"))"

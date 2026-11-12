#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"
require_commands curl jq openssl docker
BASE_URL="${1:-http://localhost:18081}"
LOCK_WAIT_SECONDS="${2:-2}"
LOCK_HOLD_SECONDS="${3:-7}"
[[ "$LOCK_WAIT_SECONDS" =~ ^[1-9]$ ]] || { echo 'Lock wait seconds must be 1-9' >&2; exit 1; }
[[ "$LOCK_HOLD_SECONDS" =~ ^([4-9]|[12][0-9]|30)$ ]] || { echo 'Lock hold seconds must be 4-30' >&2; exit 1; }

temp_dir="$(new_temp_dir)"
previous_timeout="${DATABASE_LOCK_WAIT_TIMEOUT_SECONDS-}"
restore() {
  rm -rf -- "$temp_dir"
  if [[ -n "$previous_timeout" ]]; then export DATABASE_LOCK_WAIT_TIMEOUT_SECONDS="$previous_timeout"; else unset DATABASE_LOCK_WAIT_TIMEOUT_SECONDS; fi
  compose up -d --no-deps --force-recreate commerce-service >/dev/null
}
trap restore EXIT

mysql_value() {
  compose exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse "$1" commerceflow 2>/dev/null | tr -d '\r'
}
hold_row() {
  local table="$1" id="$2"
  compose exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse \
    "START TRANSACTION; SELECT id FROM $table WHERE id=$id FOR UPDATE; DO SLEEP($LOCK_HOLD_SECONDS); COMMIT;" commerceflow \
    >/dev/null 2>&1 &
  LOCK_PID=$!
  sleep 1
}
new_order() {
  json_request POST "$BASE_URL/api/cart/items" "$customer_jar" "$customer_header" "$customer_token" \
    '{"productId":1,"quantity":1,"note":"lock-timeout-lab"}' >/dev/null
  json_request POST "$BASE_URL/api/purchase-orders/checkout" "$customer_jar" "$customer_header" "$customer_token" '{}'
}
assert_retryable() {
  local label="$1" status="$2" headers="$3" body="$4"
  [[ "$status" == 503 ]] || { echo "$label expected HTTP 503, received $status: $(cat "$body")" >&2; exit 1; }
  [[ "$(jq -r '.code' "$body")" == DATABASE_CONCURRENCY_RETRY ]] || { echo "$label missing retry code" >&2; exit 1; }
  grep -Eiq '^retry-after: *1\r?$' "$headers" || { echo "$label missing Retry-After" >&2; exit 1; }
}

export DATABASE_LOCK_WAIT_TIMEOUT_SECONDS="$LOCK_WAIT_SECONDS"
compose up -d --no-deps --force-recreate commerce-service >/dev/null
for _ in $(seq 1 30); do curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1 && break; sleep 1; done

customer_jar="$temp_dir/customer.cookies"; merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
IFS=$'\t' read -r merchant_header merchant_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
available="$(json_request GET "$BASE_URL/api/merchant/inventory" "$merchant_jar" | jq -r '.[]|select(.productId==1)|.stockAvailable')"
if (( available < 10 )); then
  json_request POST "$BASE_URL/api/merchant/inventory/1/adjustments" "$merchant_jar" "$merchant_header" "$merchant_token" \
    "$(jq -nc --argjson delta "$((10-available))" '{delta:$delta,reason:"Prepare lock timeout lab inventory"}')" >/dev/null
fi
if ! json_request GET "$BASE_URL/api/addresses/selected" "$customer_jar" >/dev/null 2>&1; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$customer_jar" "$customer_header" "$customer_token" \
    '{"recipientName":"Lock Lab","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"1 Lock Road","addressLine2":"","postalCode":"200120","label":"Lock Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r '.id'<<<"$address")/select" "$customer_jar" "$customer_header" "$customer_token" '{}' >/dev/null
fi

cancel_order="$(new_order)"; cancel_id="$(jq -r '.id'<<<"$cancel_order")"
hold_row shop_orders "$cancel_id"
cancel_status="$(curl -sS -D "$temp_dir/cancel.headers" -o "$temp_dir/cancel.body" -w '%{http_code}' -X POST \
  -b "$customer_jar" -c "$customer_jar" -H "$customer_header: $customer_token" -H 'Content-Type: application/json' \
  --data '{"reason":"lock timeout retry lab"}' "$BASE_URL/api/purchase-orders/$cancel_id/cancel")"
assert_retryable 'Cancellation lock wait' "$cancel_status" "$temp_dir/cancel.headers" "$temp_dir/cancel.body"
[[ "$(mysql_value "SELECT CONCAT(status,':',(SELECT COUNT(*) FROM order_events WHERE order_id=$cancel_id AND event_type IN ('ORDER_CANCELLING','ORDER_CANCELLED'))) FROM shop_orders WHERE id=$cancel_id")" == PENDING_PAYMENT:0 ]] || { echo 'Timed-out cancellation left partial state' >&2; exit 1; }
wait "$LOCK_PID"
[[ "$(json_request POST "$BASE_URL/api/purchase-orders/$cancel_id/cancel" "$customer_jar" "$customer_header" "$customer_token" '{"reason":"lock timeout retry lab"}' | jq -r '.status')" == CANCELLED ]] || { echo 'Cancellation retry failed' >&2; exit 1; }

paid_order="$(new_order)"; paid_id="$(jq -r '.id'<<<"$paid_order")"
payment="$(json_request POST "$BASE_URL/api/purchase-orders/$paid_id/payments" "$customer_jar" "$customer_header" "$customer_token" \
  "$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')")"
notice="$(jq -nc --arg id "LOCK-$(uuid_hex)" --arg tx "$(jq -r '.payment.channelTransactionNo'<<<"$payment")" --argjson amount "$(jq -r '.payment.amount'<<<"$payment")" \
  '{notificationId:$id,channelTransactionNo:$tx,channel:"ALIPAY",status:"SUCCESS",amount:$amount,currency:"CNY",failureReason:""}')"
signature="$(printf '%s' "$notice" | openssl dgst -sha256 -hmac 'demo-platform-callback-secret' -binary | openssl base64 -A)"
payment_id="$(mysql_value "SELECT id FROM payment_records WHERE order_id=$paid_id")"
hold_row payment_records "$payment_id"
callback_status="$(curl -sS -D "$temp_dir/callback.headers" -o "$temp_dir/callback.body" -w '%{http_code}' -X POST \
  -H "X-Callback-Signature: $signature" -H 'Content-Type: application/json' --data "$notice" "$BASE_URL/api/payment-notifications/callback")"
assert_retryable 'Payment callback lock wait' "$callback_status" "$temp_dir/callback.headers" "$temp_dir/callback.body"
[[ "$(mysql_value "SELECT CONCAT(o.status,':',p.status,':',(SELECT COUNT(*) FROM payment_notifications WHERE payment_id=p.id)) FROM shop_orders o JOIN payment_records p ON p.order_id=o.id WHERE o.id=$paid_id")" == PENDING_PAYMENT:PENDING:0 ]] || { echo 'Timed-out callback left partial state' >&2; exit 1; }
wait "$LOCK_PID"
[[ "$(curl -fsS -H "X-Callback-Signature: $signature" -H 'Content-Type: application/json' --data "$notice" "$BASE_URL/api/payment-notifications/callback" | jq -r '.result')" == PROCESSED ]] || { echo 'Callback retry failed' >&2; exit 1; }
[[ "$(mysql_value "SELECT CONCAT(o.status,':',p.status,':',(SELECT COUNT(*) FROM payment_notifications WHERE payment_id=p.id)) FROM shop_orders o JOIN payment_records p ON p.order_id=o.id WHERE o.id=$paid_id")" == PAID:SUCCESS:1 ]] || { echo 'Payment retry audit failed' >&2; exit 1; }
echo 'Payment/cancellation lock timeout verification passed: HTTP 503 retry contract, rollback and successful retry.'

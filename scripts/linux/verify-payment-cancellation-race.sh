#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq
BASE_URL="${1:-http://localhost:14200}"
PAYMENT_BASE_URL="${2:-http://localhost:14201}"
ITERATIONS="${3:-12}"
[[ "$ITERATIONS" =~ ^[0-9]+$ ]] && (( ITERATIONS >= 1 && ITERATIONS <= 100 )) || {
  echo 'Iterations must be between 1 and 100' >&2; exit 1;
}

temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
customer_jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$customer_jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$customer_jar" "$customer_header" "$customer_token" '{"recipientName":"Race Tester","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Concurrency Road","addressLine2":"","postalCode":"200120","label":"Race","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$customer_jar" "$customer_header" "$customer_token" '{}' >/dev/null
fi

declare -A outcomes=()
for ((iteration=1; iteration<=ITERATIONS; iteration++)); do
  json_request POST "$BASE_URL/api/cart/items" "$customer_jar" "$customer_header" "$customer_token" '{"productId":1,"quantity":1,"note":"payment-cancellation-race"}' >/dev/null
  order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$customer_jar" "$customer_header" "$customer_token" '{}')"
  order_id="$(jq -r .id <<<"$order")"
  payment_body="$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
  pending="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/payments" "$customer_jar" "$customer_header" "$customer_token" "$payment_body")"
  [[ "$(jq -r .payment.status <<<"$pending")" == 'PENDING' ]] || { echo "Iteration $iteration did not create a pending payment" >&2; exit 1; }
  checkout_url="$(jq -r .payment.checkoutUrl <<<"$pending")"
  checkout_token="${checkout_url%%\?*}"; checkout_token="${checkout_token##*/}"

  case $((iteration % 3)) in
    0) cancel_delay=0; payment_delay=250 ;;
    1) cancel_delay=200; payment_delay=0 ;;
    *) cancel_delay=$((RANDOM % 121)); payment_delay=$((RANDOM % 121)) ;;
  esac

  race_dir="$temp_dir/race-$iteration"; mkdir -p "$race_dir"
  gate="$race_dir/go"
  (
    while [[ ! -e "$gate" ]]; do sleep 0.01; done
    sleep "$(printf '0.%03d' "$cancel_delay")"
    curl -sS -o "$race_dir/cancel.json" -w '%{http_code}' -b "$customer_jar" -c "$customer_jar" \
      -H "$customer_header: $customer_token" -H 'Content-Type: application/json' \
      -X POST "$BASE_URL/api/purchase-orders/$order_id/cancel" \
      --data '{"reason":"randomized payment cancellation race"}' >"$race_dir/cancel.code"
  ) & cancel_pid=$!
  (
    while [[ ! -e "$gate" ]]; do sleep 0.01; done
    sleep "$(printf '0.%03d' "$payment_delay")"
    curl -sS -o "$race_dir/payment.json" -w '%{http_code}' \
      -X POST "$PAYMENT_BASE_URL/api/checkout/$checkout_token/complete?success=true" >"$race_dir/payment.code"
  ) & payment_pid=$!
  touch "$gate"
  wait "$cancel_pid" || true
  wait "$payment_pid" || true

  deadline=$((SECONDS + 15))
  while :; do
    resolved="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$customer_jar")"
    order_status="$(jq -r .status <<<"$resolved")"
    [[ "$order_status" != 'PENDING_PAYMENT' && "$order_status" != 'CANCELLING' ]] && break
    (( SECONDS >= deadline )) && break
    sleep 0.2
  done
  payment_status="$(jq -r '.payment.status // "NONE"' <<<"$resolved")"
  valid=false
  case "$order_status/$payment_status" in
    CANCELLED/CLOSED|PAID/SUCCESS) valid=true ;;
    REFUNDING/SUCCESS) jq -e '.refund != null' <<<"$resolved" >/dev/null && valid=true ;;
    REFUNDED/SUCCESS) jq -e '.refund.status == "SUCCEEDED"' <<<"$resolved" >/dev/null && valid=true ;;
  esac
  if [[ "$valid" != true ]]; then
    echo "Iteration $iteration is inconsistent: order=$order_status payment=$payment_status cancel-http=$(cat "$race_dir/cancel.code") payment-http=$(cat "$race_dir/payment.code")" >&2
    exit 1
  fi
  key="$order_status/$payment_status"; outcomes[$key]=$(( ${outcomes[$key]:-0} + 1 ))
  echo "[$iteration/$ITERATIONS] order=$(jq -r .orderNumber <<<"$resolved") outcome=$key delays=${cancel_delay}ms/${payment_delay}ms"
done

summary=''
for key in "${!outcomes[@]}"; do summary+="${summary:+, }$key=${outcomes[$key]}"; done
echo "Payment/cancellation race verification passed: iterations=$ITERATIONS; $summary"

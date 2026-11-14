#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$jar")"
merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r merchant_header merchant_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
available="$(json_request GET "$BASE_URL/api/merchant/inventory" "$merchant_jar" | jq -r '.[] | select(.productId == 1) | .stockAvailable')"
if ((available < 8)); then
  json_request POST "$BASE_URL/api/merchant/inventory/1/adjustments" "$merchant_jar" "$merchant_header" "$merchant_token" \
    "$(jq -nc --argjson delta "$((8 - available))" '{delta:$delta,reason:"Prepare payment outcomes lab inventory"}')" >/dev/null
fi
selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$jar" "$csrf_header" "$csrf_token" '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$jar" "$csrf_header" "$csrf_token" '{}' >/dev/null
fi

for pair in FAILED:FAILED INSUFFICIENT_FUNDS:FAILED CANCELLED:CLOSED TIMEOUT:EXPIRED; do
  outcome="${pair%%:*}"; expected="${pair##*:}"
  json_request POST "$BASE_URL/api/cart/items" "$jar" "$csrf_header" "$csrf_token" '{"productId":1,"quantity":1,"note":"outcome-lab"}' >/dev/null
  order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$jar" "$csrf_header" "$csrf_token" '{}')"
  payment="$(json_request POST "$BASE_URL/api/purchase-orders/$(jq -r .id <<<"$order")/payments" "$jar" "$csrf_header" "$csrf_token" "$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')")"
  token="$(jq -r '.payment.checkoutUrl | split("/")[-1] | split("?")[0]' <<<"$payment")"
  curl -fsS -X POST "http://localhost:14201/api/checkout/$token/complete?outcome=$outcome" >/dev/null
  resolved="$(json_request GET "$BASE_URL/api/purchase-orders/$(jq -r .id <<<"$order")" "$jar")"
  jq -e --arg expected "$expected" '.payment.status == $expected and .status == "PENDING_PAYMENT"' <<<"$resolved" >/dev/null || { echo "Unexpected $outcome result" >&2; exit 1; }
done
echo '05A-6 outcome verification passed: FAILED, INSUFFICIENT_FUNDS, CANCELLED, TIMEOUT'

#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$jar")"

selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$jar" "$csrf_header" "$csrf_token" '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$jar" "$csrf_header" "$csrf_token" '{}' >/dev/null
fi
json_request POST "$BASE_URL/api/cart/items" "$jar" "$csrf_header" "$csrf_token" '{"productId":1,"quantity":1,"note":"05A-4"}' >/dev/null
order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$jar" "$csrf_header" "$csrf_token" '{}')"
payment_body="$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
payment="$(json_request POST "$BASE_URL/api/purchase-orders/$(jq -r .id <<<"$order")/payments" "$jar" "$csrf_header" "$csrf_token" "$payment_body")"
jq -e '.status == "PENDING_PAYMENT" and .payment.status == "PENDING" and (.payment.checkoutUrl | length > 0)' <<<"$payment" >/dev/null || { echo 'Payment was not left pending for callback' >&2; exit 1; }
token="$(jq -r '.payment.checkoutUrl | split("/")[-1] | split("?")[0]' <<<"$payment")"
curl -fsS -X POST "http://localhost:14201/api/checkout/$token/complete?success=true" >/dev/null
resolved="$(json_request GET "$BASE_URL/api/purchase-orders/$(jq -r .id <<<"$order")" "$jar")"
jq -e '.status == "PAID" and .payment.status == "SUCCESS"' <<<"$resolved" >/dev/null || { echo 'Signed callback did not apply payment' >&2; exit 1; }
assert_status 'invalid callback signature rejected' 401 "$(http_status POST "$BASE_URL/api/payment-notifications/callback" '' 'X-Callback-Signature' bad '{}')"
echo "05A-4 verification passed: order=$(jq -r .orderNumber <<<"$order"), pending-before-callback=true, paid-after-signed-callback=true, invalid-signature=401"

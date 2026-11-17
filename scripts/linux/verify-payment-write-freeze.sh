#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
PAYMENT_BASE_URL="${2:-${PAYMENT_UI_BASE_URL:-http://localhost:14201}}"
if [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
  export WSLENV="${WSLENV:+$WSLENV:}PAYMENT_WRITE_FROZEN"
fi
temp_dir="$(new_temp_dir)"
jar="$temp_dir/customer.cookies"

cleanup() {
  export PAYMENT_WRITE_FROZEN=false
  compose up -d --no-deps --force-recreate --wait commerce-service >/dev/null
  unset PAYMENT_WRITE_FROZEN
  rm -rf -- "$temp_dir"
}
trap cleanup EXIT

compose up -d --wait commerce-service commerce-portal-angular \
  payment-simulator-service payment-simulator-web >/dev/null
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$jar")"
selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$jar" "$csrf_header" "$csrf_token" \
    '{"recipientName":"Freeze Test","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" \
    "$jar" "$csrf_header" "$csrf_token" '{}' >/dev/null
fi
json_request POST "$BASE_URL/api/cart/items" "$jar" "$csrf_header" "$csrf_token" \
  '{"productId":1,"quantity":1,"note":"payment write freeze"}' >/dev/null
order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$jar" \
  "$csrf_header" "$csrf_token" '{}')"
order_id="$(jq -r .id <<<"$order")"
payment_body="$(jq -nc --arg key "$(uuid_hex)" \
  '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
payment="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/payments" "$jar" \
  "$csrf_header" "$csrf_token" "$payment_body")"
token="$(jq -r '.payment.checkoutUrl | split("/")[-1] | split("?")[0]' <<<"$payment")"

export PAYMENT_WRITE_FROZEN=true
compose up -d --no-deps --force-recreate --wait commerce-service >/dev/null
rm -f -- "$jar"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$jar")"
assert_status 'payment creation frozen' 503 "$(http_status POST \
  "$BASE_URL/api/purchase-orders/$order_id/payments" "$jar" "$csrf_header" "$csrf_token" "$payment_body")"
assert_status 'payment close frozen' 503 "$(http_status POST \
  "$BASE_URL/api/purchase-orders/$order_id/cancel" "$jar" "$csrf_header" "$csrf_token" \
  '{"reason":"freeze verification"}')"
refund_body="$(jq -nc --arg key "$(uuid_hex)" \
  '{idempotencyKey:$key,reasonCode:"CUSTOMER_CANCELLED",reasonDetail:"freeze verification"}')"
assert_status 'refund creation frozen' 503 "$(http_status POST \
  "$BASE_URL/api/purchase-orders/$order_id/refunds" "$jar" "$csrf_header" "$csrf_token" "$refund_body")"
before="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$jar")"
jq -e '.status == "PENDING_PAYMENT" and .payment.status == "PENDING"' <<<"$before" >/dev/null || {
  echo 'Rejected writes changed the order or payment fact' >&2
  exit 1
}

curl -fsS -X POST "$PAYMENT_BASE_URL/api/checkout/$token/complete?success=true" >/dev/null
for _ in {1..60}; do
  after="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$jar")"
  jq -e '.status == "PAID" and .payment.status == "SUCCESS"' <<<"$after" >/dev/null && break
  sleep 0.5
done
jq -e '.status == "PAID" and .payment.status == "SUCCESS"' <<<"$after" >/dev/null || {
  echo 'Signed callback did not drain while writes were frozen' >&2
  exit 1
}
echo "Payment write freeze verification passed: create/close/refund=503, callbacks drain, order=$(jq -r .orderNumber <<<"$order"); CUTOVER was not performed."

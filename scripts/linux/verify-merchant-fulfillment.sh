#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT

merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r merchant_header merchant_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
orders="$(json_request GET "$BASE_URL/api/merchant/orders" "$merchant_jar")"
paid_id="$(jq -r '[.[] | select(.status == "PAID")][0].id // empty' <<<"$orders")"
[[ -n "$paid_id" ]] || { echo 'Merchant cannot see the paid order' >&2; exit 1; }
confirmed="$(json_request POST "$BASE_URL/api/merchant/orders/$paid_id/confirm" "$merchant_jar" "$merchant_header" "$merchant_token" '{}')"
[[ "$(jq -r .status <<<"$confirmed")" == CONFIRMED ]] || { echo 'Confirm transition failed' >&2; exit 1; }
initial_tracking="CFL-$paid_id-A"; updated_tracking="CFL-$paid_id-B"
shipped="$(json_request POST "$BASE_URL/api/merchant/orders/$paid_id/ship" "$merchant_jar" "$merchant_header" "$merchant_token" "$(jq -nc --arg tracking "$initial_tracking" '{carrier:"CommerceFlow Express",trackingNumber:$tracking}')")"
[[ "$(jq -r .status <<<"$shipped")" == SHIPPED ]] || { echo 'Ship transition failed' >&2; exit 1; }
updated="$(json_request PUT "$BASE_URL/api/merchant/orders/$paid_id/shipment" "$merchant_jar" "$merchant_header" "$merchant_token" "$(jq -nc --arg tracking "$updated_tracking" '{carrier:"CommerceFlow Priority",trackingNumber:$tracking}')")"
[[ "$(jq -r .carrier <<<"$updated")" == 'CommerceFlow Priority' && "$(jq -r .trackingNumber <<<"$updated")" == "$updated_tracking" ]] || { echo 'Shipment update failed' >&2; exit 1; }
for event_type in MERCHANT_CONFIRMED ORDER_SHIPPED SHIPMENT_UPDATED; do
  jq -e --arg event_type "$event_type" '.events | any(.eventType == $event_type)' <<<"$updated" >/dev/null || { echo "Missing fulfillment event: $event_type" >&2; exit 1; }
done
updated_event_count="$(jq '[.events[] | select(.eventType == "SHIPMENT_UPDATED")] | length' <<<"$updated")"
unchanged="$(json_request PUT "$BASE_URL/api/merchant/orders/$paid_id/shipment" "$merchant_jar" "$merchant_header" "$merchant_token" "$(jq -nc --arg tracking " $updated_tracking " '{carrier:" CommerceFlow Priority ",trackingNumber:$tracking}')")"
[[ "$(jq '[.events[] | select(.eventType == "SHIPMENT_UPDATED")] | length' <<<"$unchanged")" == "$updated_event_count" ]] || { echo 'Unchanged shipment created a duplicate timeline event' >&2; exit 1; }

customer_jar="$temp_dir/customer.cookies"
storefront_login "$BASE_URL" demo@example.com "$customer_jar" >/dev/null
assert_status 'customer forbidden from merchant orders' 403 "$(http_status GET "$BASE_URL/api/merchant/orders" "$customer_jar")"
echo 'Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, shipment updated, timeline audited, customer forbidden=403'

#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL" "${PAYMENT_UI_BASE_URL:-http://localhost:14201}"
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
shipment_url="$BASE_URL/api/merchant/orders/$paid_id/shipment"
version="$(jq -r .shipmentVersion <<<"$shipped")"
first_body="$(jq -nc --arg t "$updated_tracking" --argjson v "$version" '{carrier:"CommerceFlow Priority",trackingNumber:$t,expectedVersion:$v}')"
first="$(json_request PUT "$shipment_url" "$merchant_jar" "$merchant_header" "$merchant_token" "$first_body")"
[[ "$(jq -r .trackingNumber <<<"$first")" == "$updated_tracking" && "$(jq -r .shipmentVersion <<<"$first")" == "$((version+1))" ]] || { echo 'Shipment version did not advance' >&2; exit 1; }
updated_tracking="CFL-$paid_id-C"
latest_body="$(jq -nc --arg t "$updated_tracking" --argjson v "$((version+1))" '{carrier:"CommerceFlow Priority",trackingNumber:$t,expectedVersion:$v}')"
updated="$(json_request PUT "$shipment_url" "$merchant_jar" "$merchant_header" "$merchant_token" "$latest_body")"
[[ "$(jq -r .trackingNumber <<<"$updated")" == "$updated_tracking" && "$(jq -r .shipmentVersion <<<"$updated")" == "$((version+2))" ]] || { echo 'Second shipment version did not advance' >&2; exit 1; }
assert_status 'stale shipment request rejected' 409 "$(http_status PUT "$shipment_url" "$merchant_jar" "$merchant_header" "$merchant_token" "$first_body")"
for event_type in MERCHANT_CONFIRMED ORDER_SHIPPED SHIPMENT_UPDATED; do
  jq -e --arg event_type "$event_type" '.events | any(.eventType == $event_type)' <<<"$updated" >/dev/null || { echo "Missing fulfillment event: $event_type" >&2; exit 1; }
done
for event_type in MERCHANT_CONFIRMED ORDER_SHIPPED; do
  jq -e --arg event_type "$event_type" '[.events[] | select(.eventType == $event_type)] | length == 1' \
    <<<"$updated" >/dev/null || { echo "Expected exactly one $event_type event" >&2; exit 1; }
done
updated_event_count="$(jq '[.events[] | select(.eventType == "SHIPMENT_UPDATED")] | length' <<<"$updated")"
[[ "$updated_event_count" == 2 ]] || { echo 'Expected two shipment update events' >&2; exit 1; }
unchanged="$(json_request PUT "$shipment_url" "$merchant_jar" "$merchant_header" "$merchant_token" "$(jq -nc --arg t " $updated_tracking " --argjson v "$((version+2))" '{carrier:" CommerceFlow Priority ",trackingNumber:$t,expectedVersion:$v}')")"
[[ "$(jq -r .trackingNumber <<<"$unchanged")" == "$updated_tracking" && "$(jq -r .shipmentVersion <<<"$unchanged")" == "$((version+2))" ]] || { echo 'Stale write changed shipment details' >&2; exit 1; }
[[ "$(jq '[.events[] | select(.eventType == "SHIPMENT_UPDATED")] | length' <<<"$unchanged")" == "$updated_event_count" ]] || { echo 'Unchanged shipment created a duplicate timeline event' >&2; exit 1; }

customer_jar="$temp_dir/customer.cookies"
storefront_login "$BASE_URL" demo@example.com "$customer_jar" >/dev/null
assert_status 'customer forbidden from merchant orders' 403 "$(http_status GET "$BASE_URL/api/merchant/orders" "$customer_jar")"
echo 'Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, shipment updated, timeline audited, customer forbidden=403'

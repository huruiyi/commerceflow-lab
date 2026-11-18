#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
payment_result="$("$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL" "${PAYMENT_UI_BASE_URL:-http://localhost:14201}")"
printf '%s\n' "$payment_result"
[[ "$payment_result" =~ order=(ORD-[A-Z0-9]+) ]] || { echo 'Paid order number missing from payment verification' >&2; exit 1; }
sample_number="${BASH_REMATCH[1]}"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT

merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r merchant_header merchant_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
orders="$(json_request GET "$BASE_URL/api/merchant/orders" "$merchant_jar")"
paid_id="$(jq -r --arg sample "$sample_number" '[.[] | select(.status == "PAID" and .orderNumber == $sample)][0].id // empty' <<<"$orders")"
[[ -n "$paid_id" ]] || { echo 'Merchant cannot see the paid sample order' >&2; exit 1; }
mutate_fulfillment() {
  local method="$1" path="$2" body="$3" code location status asynchronous=false deadline
  code="$(curl -sS -w '%{http_code}' -D "$temp_dir/headers" -o "$temp_dir/body" -X "$method" \
    -b "$merchant_jar" -H "$merchant_header: $merchant_token" -H 'Content-Type: application/json' \
    -d "$body" "$BASE_URL$path")"
  case "$code" in
    200) ;;
    202)
      asynchronous=true
      location="$(awk 'tolower($1) == "location:" { sub(/\r$/, "", $2); print $2 }' "$temp_dir/headers")"
      [[ "$location" == /api/merchant/orders/* ]] || { echo 'Missing fulfillment status location' >&2; return 1; }
      deadline=$((SECONDS + 60))
      while (( SECONDS < deadline )); do
        status="$(json_request GET "$BASE_URL$location" "$merchant_jar" | jq -r .status)"
        [[ "$status" == COMPLETED ]] && break
        [[ "$status" == PENDING ]] || { echo "Fulfillment operation ended as $status" >&2; return 1; }
        sleep 1
      done
      [[ "$status" == COMPLETED ]] || { echo 'Fulfillment operation did not complete' >&2; return 1; }
      ;;
    *) echo "Unexpected fulfillment status: $code" >&2; return 1 ;;
  esac
  jq -c --argjson async "$asynchronous" '{order:.,async:$async}' "$temp_dir/body"
}
confirmed="$(mutate_fulfillment POST "/api/merchant/orders/$paid_id/confirm" '{}')"
jq -e '.async or .order.status == "CONFIRMED"' <<<"$confirmed" >/dev/null || { echo 'Confirm transition failed' >&2; exit 1; }
initial_tracking="CFL-$paid_id-A"; updated_tracking="CFL-$paid_id-B"
ship_path="/api/merchant/orders/$paid_id/ship"
ship_body="$(jq -nc --arg tracking "$initial_tracking" '{carrier:"CommerceFlow Express",trackingNumber:$tracking}')"
ship="$(mutate_fulfillment POST "$ship_path" "$ship_body")"
if jq -e '.async' <<<"$ship" >/dev/null; then ship="$(mutate_fulfillment POST "$ship_path" "$ship_body")"; fi
shipped="$(jq -c '.order' <<<"$ship")"
[[ "$(jq -r .status <<<"$shipped")" == SHIPPED ]] || { echo 'Ship transition failed' >&2; exit 1; }
shipment_path="/api/merchant/orders/$paid_id/shipment"
shipment_url="$BASE_URL$shipment_path"
update_verified_shipment() {
  local body="$1" expected="$2" result
  result="$(mutate_fulfillment PUT "$shipment_path" "$body")"
  if jq -e '.async' <<<"$result" >/dev/null; then
    body="$(jq -c --argjson version "$expected" '.expectedVersion = $version' <<<"$body")"
    result="$(mutate_fulfillment PUT "$shipment_path" "$body")"
  fi
  jq -c '.order' <<<"$result"
}
version="$(jq -r .shipmentVersion <<<"$shipped")"
first_body="$(jq -nc --arg t "$updated_tracking" --argjson v "$version" '{carrier:"CommerceFlow Priority",trackingNumber:$t,expectedVersion:$v}')"
first="$(update_verified_shipment "$first_body" "$((version+1))")"
[[ "$(jq -r .trackingNumber <<<"$first")" == "$updated_tracking" && "$(jq -r .shipmentVersion <<<"$first")" == "$((version+1))" ]] || { echo 'Shipment version did not advance' >&2; exit 1; }
updated_tracking="CFL-$paid_id-C"
latest_body="$(jq -nc --arg t "$updated_tracking" --argjson v "$((version+1))" '{carrier:"CommerceFlow Priority",trackingNumber:$t,expectedVersion:$v}')"
updated="$(update_verified_shipment "$latest_body" "$((version+2))")"
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
echo "Chapter 06 verification passed: PAID -> CONFIRMED -> SHIPPED, shipment updated, timeline audited, customer forbidden=403 (order=$sample_number)"

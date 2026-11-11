#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
"$SCRIPT_DIR/verify-merchant-fulfillment.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$jar")"
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$jar")"
shipped_id="$(jq -r '[.[] | select(.status == "SHIPPED")][0].id // empty' <<<"$orders")"
[[ -n "$shipped_id" ]] || { echo 'Customer cannot see shipped order' >&2; exit 1; }
received="$(json_request POST "$BASE_URL/api/purchase-orders/$shipped_id/receive" "$jar" "$csrf_header" "$csrf_token" '{}')"
[[ "$(jq -r .status <<<"$received")" == RECEIVED ]] || { echo 'Receive transition failed' >&2; exit 1; }
item_id="$(jq -r '.items[0].id' <<<"$received")"; product_id="$(jq -r '.items[0].productId' <<<"$received")"
review="$(json_request POST "$BASE_URL/api/order-items/$item_id/reviews" "$jar" "$csrf_header" "$csrf_token" '{"rating":5,"comment":"Excellent learning product"}')"
[[ "$(jq -r .rating <<<"$review")" == 5 ]] || { echo 'Review insert failed' >&2; exit 1; }
assert_status 'duplicate review rejected' 409 "$(http_status POST "$BASE_URL/api/order-items/$item_id/reviews" "$jar" "$csrf_header" "$csrf_token" '{"rating":4,"comment":"duplicate"}')"
assert_status 'invalid rating rejected' 400 "$(http_status POST "$BASE_URL/api/order-items/$item_id/reviews" "$jar" "$csrf_header" "$csrf_token" '{"rating":6,"comment":"invalid"}')"
product="$(json_request GET "$BASE_URL/api/products" "$jar" | jq -c --argjson id "$product_id" '.[] | select(.id == $id)')"
jq -e '.reviewCount >= 1 and .averageRating >= 1' <<<"$product" >/dev/null || { echo 'Product rating aggregate missing' >&2; exit 1; }
echo "Chapter 07 verification passed: SHIPPED -> RECEIVED, review unique, rating validation, aggregate=$(jq -r .averageRating <<<"$product")"

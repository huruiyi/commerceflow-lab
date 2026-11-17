#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:14200}";PAYMENT_URL="${2:-http://localhost:14201}"
tmp_dir="$(mktemp -d)";trap 'rm -rf -- "$tmp_dir"' EXIT;cookies="$tmp_dir/cookies"
csrf_json="$(curl -fsS -c "$cookies" "$BASE_URL/api/auth/csrf")"
csrf_header="$(jq -r .headerName<<<"$csrf_json")";csrf_token="$(jq -r .token<<<"$csrf_json")"
curl -fsS -b "$cookies" -c "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' "$BASE_URL/api/auth/login">/dev/null
curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data '{"productId":1,"quantity":1,"note":"payment-cutover-verification"}' "$BASE_URL/api/cart/items">/dev/null
order="$(curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data '{}' "$BASE_URL/api/purchase-orders/checkout")";order_id="$(jq -r .id<<<"$order")"
payment_key="$(cat /proc/sys/kernel/random/uuid)";payment_body="$(jq -nc --arg key "$payment_key" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
payment="$(curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data "$payment_body" "$BASE_URL/api/purchase-orders/$order_id/payments")"
payment_replay="$(curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data "$payment_body" "$BASE_URL/api/purchase-orders/$order_id/payments")"
[[ "$(jq -r .payment.paymentNumber<<<"$payment")" == "$(jq -r .payment.paymentNumber<<<"$payment_replay")" ]]
token="$(jq -r .payment.checkoutUrl<<<"$payment"|sed -E 's#^.*/([^/?]+).*#\1#')";curl -fsS -X POST "$PAYMENT_URL/api/checkout/$token/complete?outcome=SUCCESS">/dev/null
deadline=$((SECONDS+45));while ((SECONDS<deadline));do current="$(curl -fsS -b "$cookies" "$BASE_URL/api/purchase-orders/$order_id")";[[ "$(jq -r .status<<<"$current")" == PAID && "$(jq -r .payment.status<<<"$current")" == SUCCESS ]]&&break;sleep 1;done
[[ "$(jq -r .status<<<"$current")" == PAID ]]
refund_key="$(cat /proc/sys/kernel/random/uuid)";refund_body="$(jq -nc --arg key "$refund_key" '{idempotencyKey:$key,reasonCode:"OTHER",reasonDetail:"CUTOVER verification"}')"
refund="$(curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data "$refund_body" "$BASE_URL/api/purchase-orders/$order_id/refunds")"
refund_replay="$(curl -fsS -b "$cookies" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' --data "$refund_body" "$BASE_URL/api/purchase-orders/$order_id/refunds")"
[[ "$(jq -r .refundNumber<<<"$refund")" == "$(jq -r .refundNumber<<<"$refund_replay")" ]]
deadline=$((SECONDS+60));while ((SECONDS<deadline));do refunds="$(curl -fsS -b "$cookies" "$BASE_URL/api/purchase-orders/$order_id/refunds")";current="$(curl -fsS -b "$cookies" "$BASE_URL/api/purchase-orders/$order_id")";[[ "$(jq -r '.[0].status'<<<"$refunds")" == SUCCEEDED && "$(jq -r '.[0].inventoryStatus'<<<"$refunds")" == REFUNDED && "$(jq -r .status<<<"$current")" == REFUNDED ]]&&break;sleep 1;done
[[ "$(jq -r .status<<<"$current")" == REFUNDED && "$(jq -r '.[0].status'<<<"$refunds")" == SUCCEEDED && "$(jq -r '.[0].inventoryStatus'<<<"$refunds")" == REFUNDED ]]
echo "Payment runtime CUTOVER passed: order=$order_id, payment replay MATCH, refund replay MATCH."

#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
PAYMENT_BASE_URL="${2:-http://localhost:14201}"
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
cookie_jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$cookie_jar")"
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$cookie_jar")"
order_id="$(jq -r '[.[] | select(.status == "PAID")][0].id // empty' <<<"$orders")"
[[ -n "$order_id" ]] || { echo 'Paid order not found' >&2; exit 1; }
key="$(uuid_hex)"; body="$(jq -nc --arg key "$key" '{idempotencyKey:$key,reasonCode:"CUSTOMER_CANCELLED",reasonDetail:"05B automated refund acceptance"}')"
refund="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/refunds" "$cookie_jar" "$csrf_header" "$csrf_token" "$body")"
[[ "$(jq -r .status <<<"$refund")" == SUCCEEDED && "$(jq -r .channelRefundNo <<<"$refund")" != null ]] || { echo 'Signed channel refund did not complete' >&2; exit 1; }
resolved="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$cookie_jar")"
jq -e '.status=="REFUNDED" and .payment.status=="SUCCESS" and (.payment.refundedAmount==.payment.amount)' <<<"$resolved" >/dev/null || { echo 'Order, payment and refund facts are inconsistent' >&2; exit 1; }
replayed="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/refunds" "$cookie_jar" "$csrf_header" "$csrf_token" "$body")"
[[ "$(jq -r .refundNumber <<<"$replayed")" == "$(jq -r .refundNumber <<<"$refund")" ]] || { echo 'Exact refund replay was not idempotent' >&2; exit 1; }
conflict="$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,reasonCode:"OTHER",reasonDetail:"conflict"}')"
assert_status 'different second refund rejected' 409 "$(http_status POST "$BASE_URL/api/purchase-orders/$order_id/refunds" "$cookie_jar" "$csrf_header" "$csrf_token" "$conflict")"
assert_status 'invalid refund callback rejected' 401 "$(http_status POST "$BASE_URL/api/refund-notifications/callback" '' 'X-Callback-Signature' 'bad' '{}')"
admin_jar="$temp_dir/admin.cookies"
IFS=$'\t' read -r admin_header admin_token <<<"$(payment_admin_login "$PAYMENT_BASE_URL" "$admin_jar")"
ledger="$(json_request GET "$PAYMENT_BASE_URL/api/admin/refunds" "$admin_jar")"
channel_refund_no="$(jq -r --arg refund "$(jq -r .refundNumber <<<"$refund")" '[.[] | select(.merchantRefundNo == $refund)][0].channelRefundNo // empty' <<<"$ledger")"
[[ -n "$channel_refund_no" ]] || { echo 'Payment refund ledger did not expose the refund' >&2; exit 1; }
json_request POST "$PAYMENT_BASE_URL/api/admin/refunds/$channel_refund_no/notify" "$admin_jar" "$admin_header" "$admin_token" '{}' >/dev/null
after_redelivery="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$cookie_jar")"
jq -e '.status=="REFUNDED" and (.payment.refundedAmount==.payment.amount)' <<<"$after_redelivery" >/dev/null || { echo 'Redelivered refund notification was not idempotent' >&2; exit 1; }
echo '05B verification passed: full refund succeeded, ledgers reconciled, replay and notification idempotent, duplicate=409, invalid-signature=401'

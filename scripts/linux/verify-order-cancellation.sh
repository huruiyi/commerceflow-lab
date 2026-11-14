#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
customer_jar="$temp_dir/customer.cookies"; admin_jar="$temp_dir/admin.cookies"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"

selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$customer_jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$customer_jar" "$customer_header" "$customer_token" '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$customer_jar" "$customer_header" "$customer_token" '{}' >/dev/null
fi

new_order() {
  json_request POST "$BASE_URL/api/cart/items" "$customer_jar" "$customer_header" "$customer_token" '{"productId":1,"quantity":1,"note":"cancellation-acceptance"}' >/dev/null
  json_request POST "$BASE_URL/api/purchase-orders/checkout" "$customer_jar" "$customer_header" "$customer_token" '{}'
}

wait_cancellation_notification() {
  local order_id="$1" notifications='' count=0 deadline=$((SECONDS + 30))
  while ((SECONDS < deadline)); do
    notifications="$(json_request GET "$BASE_URL/api/notifications" "$customer_jar")"
    count="$(jq --argjson id "$order_id" '[.[] | select(.orderId == $id and .notificationType == "ORDER_CANCELLED")] | length' <<<"$notifications")"
    [[ "$count" == 1 ]] && { jq -c --argjson id "$order_id" '.[] | select(.orderId == $id and .notificationType == "ORDER_CANCELLED")' <<<"$notifications"; return; }
    [[ "$count" == 0 ]] || { echo 'Cancellation notification was not idempotent' >&2; return 1; }
    sleep 0.5
  done
  echo 'Versioned cancellation notification was not consumed' >&2; return 1
}

order="$(new_order)"
jq -e '.status == "PENDING_PAYMENT" and (.paymentDueAt | length > 0) and (.availableActions | index("PAY")) != null and (.availableActions | index("CANCEL_ORDER")) != null' <<<"$order" >/dev/null || { echo 'Checkout did not expose the payment deadline and actions' >&2; exit 1; }
order_id="$(jq -r .id <<<"$order")"
cancelled="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/cancel" "$customer_jar" "$customer_header" "$customer_token" '{"reason":"customer changed mind"}')"
replayed="$(json_request POST "$BASE_URL/api/purchase-orders/$order_id/cancel" "$customer_jar" "$customer_header" "$customer_token" '{"reason":"customer changed mind"}')"
jq -e '.status == "CANCELLED" and (.cancelledAt | length > 0)' <<<"$cancelled" >/dev/null || { echo 'Cancellation failed' >&2; exit 1; }
jq -e '(.availableActions | index("PAY")) == null and (.availableActions | index("CANCEL_ORDER")) == null' <<<"$cancelled" >/dev/null || { echo 'Cancelled order still exposes mutating actions' >&2; exit 1; }
jq -e '.status == "CANCELLED"' <<<"$replayed" >/dev/null || { echo 'Cancellation replay was not idempotent' >&2; exit 1; }
notification="$(wait_cancellation_notification "$order_id")"
jq -e '.message == "customer changed mind"' <<<"$notification" >/dev/null || { echo 'Cancellation notification lost its command payload' >&2; exit 1; }

IFS=$'\t' read -r admin_header admin_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
tasks="$(json_request GET "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" "$admin_jar")"
jq -e --arg id "$order_id" '.[] | select(.aggregateId == $id and .status == "CANCELLED" and .primaryTriggerSnapshot == "DATABASE")' <<<"$tasks" >/dev/null || { echo 'Payment-timeout task was not cancelled' >&2; exit 1; }

order_with_payment="$(new_order)"; paid_order_id="$(jq -r .id <<<"$order_with_payment")"
payment_body="$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
pending="$(json_request POST "$BASE_URL/api/purchase-orders/$paid_order_id/payments" "$customer_jar" "$customer_header" "$customer_token" "$payment_body")"
jq -e '.payment.status == "PENDING"' <<<"$pending" >/dev/null || { echo 'Acceptance payment was not pending' >&2; exit 1; }
closed="$(json_request POST "$BASE_URL/api/purchase-orders/$paid_order_id/cancel" "$customer_jar" "$customer_header" "$customer_token" '{"reason":"close pending transaction"}')"
jq -e '.status == "CANCELLED" and .payment.status == "CLOSED"' <<<"$closed" >/dev/null || { echo 'Pending transaction was not closed' >&2; exit 1; }

echo 'Order cancellation verification passed: available actions, deadline task, Outbox-backed cancellation, versioned notification consumption and pending channel close.'

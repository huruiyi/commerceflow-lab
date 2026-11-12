#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq

BASE_URL="${1:-http://localhost:14200}"
MAX_WAIT_SECONDS="${2:-30}"
temp_dir="$(new_temp_dir)"; customer_jar="$temp_dir/customer.cookies"; admin_jar="$temp_dir/admin.cookies"
original=''; admin_header=''; admin_token=''

restore_policy() {
  if [[ -n "$original" && -n "$admin_header" ]]; then
    if [[ "$(jq -r .source <<<"$original")" == DATABASE_OVERRIDE ]]; then
      body="$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' <<<"$original")"
      json_request PUT "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" "$admin_jar" "$admin_header" "$admin_token" "$body" >/dev/null || true
    else
      json_request DELETE "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" "$admin_jar" "$admin_header" "$admin_token" >/dev/null || true
    fi
  fi
  rm -rf -- "$temp_dir"
}
trap restore_policy EXIT

IFS=$'\t' read -r admin_header admin_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
original="$(json_request GET "$BASE_URL/api/admin/scheduling/policies" "$admin_jar" | jq -c '.[] | select(.taskType == "PAYMENT_TIMEOUT")')"
[[ -n "$original" ]] || { echo 'PAYMENT_TIMEOUT policy was not found' >&2; exit 1; }

policy='{"enabled":true,"primaryTrigger":"ROCKETMQ","reconciliationEnabled":false,"gracePeriodSeconds":0,"claimLeaseSeconds":30,"maxAttempts":5,"retryDelaySeconds":1}'
saved="$(json_request PUT "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" "$admin_jar" "$admin_header" "$admin_token" "$policy")"
[[ "$(jq -r .primaryTrigger <<<"$saved")" == ROCKETMQ ]] || { echo 'RocketMQ adapter is not enabled' >&2; exit 1; }

selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$customer_jar")"
if [[ -z "$(jq -r '.addressId // empty' <<<"$selected")" ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$customer_jar" "$customer_header" "$customer_token" '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$customer_jar" "$customer_header" "$customer_token" '{}' >/dev/null
fi
json_request POST "$BASE_URL/api/cart/items" "$customer_jar" "$customer_header" "$customer_token" '{"productId":1,"quantity":1,"note":"rocketmq-scheduling-acceptance"}' >/dev/null
order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$customer_jar" "$customer_header" "$customer_token" '{}')"
order_id="$(jq -r .id <<<"$order")"
[[ "$(jq -r .status <<<"$order")" == PENDING_PAYMENT ]] || { echo 'Checkout did not create a pending-payment order' >&2; exit 1; }

deadline=$((SECONDS + MAX_WAIT_SECONDS)); current="$order"
while [[ "$(jq -r .status <<<"$current")" != CANCELLED && $SECONDS -lt $deadline ]]; do
  sleep 0.5
  current="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$customer_jar")"
done
[[ "$(jq -r .status <<<"$current")" == CANCELLED ]] || { echo 'RocketMQ did not close the unpaid order; use a short ORDER_PAYMENT_TIMEOUT' >&2; exit 1; }

tasks="$(json_request GET "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" "$admin_jar")"
task="$(jq -c --arg id "$order_id" '.[] | select(.aggregateId == $id)' <<<"$tasks" | head -n1)"
jq -e '.status == "SUCCEEDED" and .primaryTriggerSnapshot == "ROCKETMQ" and .lastTriggerSource == "ROCKETMQ"' <<<"$task" >/dev/null || { echo 'Task did not complete from its RocketMQ snapshot' >&2; exit 1; }
events="$(json_request GET "$BASE_URL/api/admin/scheduling/tasks/$(jq -r .id <<<"$task")/events" "$admin_jar")"
for type in TASK_TRIGGER_PUBLISHED TASK_CLAIMED TASK_SUCCEEDED; do
  jq -e --arg type "$type" '.[] | select(.eventType == $type)' <<<"$events" >/dev/null || { echo "Missing event: $type" >&2; exit 1; }
done
echo "RocketMQ scheduling verification passed: order $(jq -r .orderNumber <<<"$order"), task $(jq -r .id <<<"$task"), broker acknowledgement, native delay delivery, atomic claim and timeout cancellation."

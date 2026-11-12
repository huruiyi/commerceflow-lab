#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:14200}"
MAX_WAIT="${2:-45}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

login() {
  local email="$1" cookie="$2" header="$3"
  curl -fsS -c "$cookie" "$BASE_URL/api/auth/csrf" > "$tmp/csrf.json"
  local token header_name
  token="$(jq -r .token "$tmp/csrf.json")"; header_name="$(jq -r .headerName "$tmp/csrf.json")"
  printf '%s: %s' "$header_name" "$token" > "$header"
  curl -fsS -b "$cookie" -c "$cookie" -H "$(cat "$header")" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"demo-password\",\"rememberMe\":false}" "$BASE_URL/api/auth/login" >/dev/null
}
put_policy() {
  curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' \
    -X PUT -d "$1" "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT"
}

login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/policies" |
  jq -c '.[] | select(.taskType=="PAYMENT_TIMEOUT")' > "$tmp/original.json"
[[ -s "$tmp/original.json" ]] || { echo 'PAYMENT_TIMEOUT policy was not found' >&2; exit 1; }
broker_stopped=false
cleanup() {
  if [[ "$broker_stopped" == true ]]; then docker compose --profile rocketmq up -d --wait rocketmq-broker >/dev/null; fi
  if [[ "$(jq -r .source "$tmp/original.json")" == DATABASE_OVERRIDE ]]; then
    put_policy "$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' "$tmp/original.json")" >/dev/null
  else
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -X DELETE "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  fi
}
trap 'status=$?; cleanup; rm -rf "$tmp"; exit $status' EXIT

put_policy '{"enabled":true,"primaryTrigger":"ROCKETMQ","reconciliationEnabled":true,"gracePeriodSeconds":2,"claimLeaseSeconds":30,"maxAttempts":5,"retryDelaySeconds":1}' >/dev/null
docker compose --profile rocketmq stop rocketmq-broker >/dev/null
broker_stopped=true

selected="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/addresses/selected")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == "" ]]; then
  address="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -d '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}' "$BASE_URL/api/addresses")"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -X PUT -d '{}' "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" >/dev/null
fi
curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1,"note":"rocketmq-reconciliation-acceptance"}' "$BASE_URL/api/cart/items" >/dev/null
order="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -d '{}' "$BASE_URL/api/purchase-orders/checkout")"
order_id="$(jq -r .id <<<"$order")"; order_number="$(jq -r .orderNumber <<<"$order")"

deadline=$((SECONDS + MAX_WAIT)); current=''
while (( SECONDS < deadline )); do
  sleep 0.5; current="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/purchase-orders/$order_id")"
  [[ "$(jq -r .status <<<"$current")" == CANCELLED ]] && break
done
[[ "$(jq -r .status <<<"$current")" == CANCELLED ]] || { echo 'Database reconciliation did not close the order' >&2; exit 1; }
task="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" | jq -c --arg id "$order_id" '.[] | select(.aggregateId==$id)' | head -n1)"
jq -e '.status=="SUCCEEDED" and .primaryTriggerSnapshot=="ROCKETMQ" and .lastTriggerSource=="DATABASE_RECONCILIATION" and .reconciliationEnabledSnapshot==true and .triggerPublishAttempts>=1 and (.triggerLastError|length)>0' <<<"$task" >/dev/null
events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$(jq -r .id <<<"$task")/events")"
jq -e 'map(.eventType) | contains(["TASK_CLAIMED","TASK_SUCCEEDED"])' <<<"$events" >/dev/null
echo "RocketMQ reconciliation verification passed: order $order_number, task $(jq -r .id <<<"$task"), publish failure recorded and Database reconciliation completed the task."

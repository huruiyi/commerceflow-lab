#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:14200}"
PAYMENT_URL="${2:-http://localhost:14201}"
MAX_WAIT="${3:-60}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
environment_names=(ORDER_PAYMENT_TIMEOUT SCHEDULING_DATABASE_INITIAL_DELAY SCHEDULING_DATABASE_FIXED_DELAY SCHEDULING_RECONCILIATION_INITIAL_DELAY)
declare -A original_values original_set
for name in "${environment_names[@]}";do
  if [[ -v "$name" ]];then original_set["$name"]=1;original_values["$name"]="${!name}"
  else original_set["$name"]=0;original_values["$name"]='';fi
done
compose(){ docker compose --project-directory "$PROJECT_ROOT" "$@"; }
login(){
  local email="$1" cookie="$2" header="$3" csrf
  csrf="$(curl -fsS -c "$cookie" "$BASE_URL/api/auth/csrf")"
  printf '%s: %s' "$(jq -r .headerName<<<"$csrf")" "$(jq -r .token<<<"$csrf")" >"$header"
  curl -fsS -b "$cookie" -c "$cookie" -H "$(cat "$header")" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"demo-password\",\"rememberMe\":false}" "$BASE_URL/api/auth/login" >/dev/null
}
put_policy(){
  local strategy="$1"
  curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' -X PUT \
    -d "{\"enabled\":true,\"primaryTrigger\":\"$strategy\",\"reconciliationEnabled\":false,\"gracePeriodSeconds\":0,\"claimLeaseSeconds\":30,\"maxAttempts\":5,\"retryDelaySeconds\":1}" \
    "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
}
restore_policy(){
  [[ -s "$tmp/original-policy.json" ]]||return
  login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header" || return
  if [[ "$(jq -r .source "$tmp/original-policy.json")" == DATABASE_OVERRIDE ]];then
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' -X PUT \
      -d "$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' "$tmp/original-policy.json")" \
      "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  else curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -X DELETE "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null;fi
}
cleanup(){
  local status=$?;set +e;restore_policy
  for name in "${environment_names[@]}";do
    if [[ "${original_set[$name]}" == 1 ]];then printf -v "$name" '%s' "${original_values[$name]}";export "$name";else unset "$name";fi
  done
  compose up -d --wait --force-recreate backend web payment-ui >/dev/null
  rm -rf -- "$tmp";exit "$status"
}
trap cleanup EXIT

export ORDER_PAYMENT_TIMEOUT=18s SCHEDULING_DATABASE_INITIAL_DELAY=1s
export SCHEDULING_DATABASE_FIXED_DELAY=1s SCHEDULING_RECONCILIATION_INITIAL_DELAY=1h
compose up -d --wait --force-recreate backend web payment-ui >/dev/null
login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/policies"|jq -c '.[]|select(.taskType=="PAYMENT_TIMEOUT")'>"$tmp/original-policy.json"
put_policy DATABASE

selected="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/addresses/selected")"
if [[ "$(jq -r '.addressId//empty'<<<"$selected")" == '' ]];then
  address="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d '{"recipientName":"Deadline Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Deadline Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}' "$BASE_URL/api/addresses")"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -X PUT -d '{}' \
    "$BASE_URL/api/addresses/$(jq -r .id<<<"$address")/select" >/dev/null
fi
curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
  -d '{"productId":1,"quantity":1,"note":"attempt-expiry-versus-order-deadline"}' "$BASE_URL/api/cart/items" >/dev/null
order="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' -d '{}' "$BASE_URL/api/purchase-orders/checkout")"
order_id="$(jq -r .id<<<"$order")"
payment(){
  local channel="$1"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg key "$(openssl rand -hex 16)" --arg channel "$channel" '{idempotencyKey:$key,simulateFailure:false,channel:$channel}')" \
    "$BASE_URL/api/purchase-orders/$order_id/payments"
}
first="$(payment ALIPAY)";first_token="$(jq -r '.payment.checkoutUrl|split("/")[-1]|split("?")[0]'<<<"$first")"
curl -fsS -X POST "$PAYMENT_URL/api/checkout/$first_token/complete?outcome=TIMEOUT" >/dev/null
expired="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/purchase-orders/$order_id")"
jq -e '.status=="PENDING_PAYMENT" and .payment.status=="EXPIRED"'<<<"$expired" >/dev/null||{ echo 'A single expired attempt closed the order'>&2;exit 1;}
retry="$(payment WECHAT_PAY)";retry_token="$(jq -r '.payment.checkoutUrl|split("/")[-1]|split("?")[0]'<<<"$retry")"
jq -e '.status=="PENDING_PAYMENT" and .payment.status=="PENDING" and .payment.channel=="WECHAT_PAY"'<<<"$retry" >/dev/null
[[ "$(curl -fsS "$PAYMENT_URL/api/checkout/$first_token"|jq -r .status)" == EXPIRED ]]

deadline=$((SECONDS+MAX_WAIT));closed="$retry"
while [[ "$(jq -r .status<<<"$closed")" != CANCELLED && $SECONDS -lt $deadline ]];do sleep 0.5;closed="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/purchase-orders/$order_id")";done
jq -e '.status=="CANCELLED" and .payment.status=="CLOSED"'<<<"$closed" >/dev/null||{ echo 'Whole-order deadline did not close the retry'>&2;exit 1;}
[[ "$(curl -fsS "$PAYMENT_URL/api/checkout/$retry_token"|jq -r .status)" == CLOSED ]]
tasks="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500")"
task="$(jq -c --arg id "$order_id" '.[]|select(.aggregateId==$id)'<<<"$tasks"|head -n1)"
jq -e '.status=="SUCCEEDED" and .lastTriggerSource=="DATABASE_SCAN" and .attempts==1'<<<"$task" >/dev/null
events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$(jq -r .id<<<"$task")/events")"
jq -e '([.[]|select(.eventType=="TASK_CLAIMED")]|length)==1 and ([.[]|select(.eventType=="TASK_SUCCEEDED")]|length)==1'<<<"$events" >/dev/null
echo "Payment attempt/deadline verification passed: ALIPAY EXPIRED -> WECHAT_PAY retry -> order deadline closed the retry and cancelled order $(jq -r .orderNumber<<<"$order") (task $(jq -r .id<<<"$task"))."

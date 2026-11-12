#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:14200}"
MAX_WAIT="${2:-120}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
environment_names=(
  ORDER_PAYMENT_TIMEOUT SCHEDULING_RABBITMQ_ENABLED SCHEDULING_ROCKETMQ_ENABLED
  SCHEDULING_MQ_CRASH_LAB_ENABLED SCHEDULING_MQ_CRASH_LAB_TASK_ID
  SCHEDULING_MQ_CRASH_LAB_PAUSE SCHEDULING_DATABASE_INITIAL_DELAY
  SCHEDULING_RECONCILIATION_INITIAL_DELAY
)
declare -A original_values original_set
for name in "${environment_names[@]}"; do
  if [[ -v "$name" ]]; then original_set["$name"]=1; original_values["$name"]="${!name}"
  else original_set["$name"]=0; original_values["$name"]=''
  fi
done

compose() {
  docker compose --project-directory "$PROJECT_ROOT" --profile rabbitmq --profile rocketmq "$@"
}

login() {
  local email="$1" cookie="$2" header="$3"
  curl -fsS -c "$cookie" "$BASE_URL/api/auth/csrf" > "$tmp/csrf.json"
  printf '%s: %s' "$(jq -r .headerName "$tmp/csrf.json")" "$(jq -r .token "$tmp/csrf.json")" > "$header"
  curl -fsS -b "$cookie" -c "$cookie" -H "$(cat "$header")" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"demo-password\",\"rememberMe\":false}" "$BASE_URL/api/auth/login" >/dev/null
}

put_policy() {
  local strategy="$1"
  curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' \
    -X PUT -d "{\"enabled\":true,\"primaryTrigger\":\"$strategy\",\"reconciliationEnabled\":false,\"gracePeriodSeconds\":0,\"claimLeaseSeconds\":30,\"maxAttempts\":5,\"retryDelaySeconds\":1}" \
    "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
}

restore_policy() {
  [[ -s "$tmp/original-policy.json" ]] || return
  if [[ "$(jq -r .source "$tmp/original-policy.json")" == DATABASE_OVERRIDE ]]; then
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' \
      -X PUT -d "$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' "$tmp/original-policy.json")" \
      "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  else
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -X DELETE \
      "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  fi
}

cleanup() {
  local status=$?
  set +e
  restore_policy
  for name in "${environment_names[@]}"; do
    if [[ "${original_set[$name]}" == 1 ]]; then printf -v "$name" '%s' "${original_values[$name]}"; export "$name"
    else unset "$name"
    fi
  done
  compose up -d --wait --force-recreate commerce-service commerce-portal-angular >/dev/null
  rm -rf -- "$tmp"
  exit "$status"
}
trap cleanup EXIT

ensure_address() {
  local selected address
  selected="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/addresses/selected")"
  [[ "$(jq -r '.addressId // empty' <<<"$selected")" != '' ]] && return
  address="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}' "$BASE_URL/api/addresses")"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -X PUT -d '{}' "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" >/dev/null
}

get_task() {
  local order_id="$1"
  curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" |
    jq -c --arg id "$order_id" '.[] | select(.aggregateId==$id)' | head -n1
}

wait_for_task_field() {
  local order_id="$1" jq_filter="$2" failure="$3" deadline=$((SECONDS + MAX_WAIT)) task=''
  while (( SECONDS < deadline )); do
    task="$(get_task "$order_id")"
    if [[ -n "$task" ]] && jq -e "$jq_filter" <<<"$task" >/dev/null; then printf '%s' "$task"; return; fi
    sleep 0.5
  done
  echo "$failure" >&2; return 1
}

wait_for_pause_event() {
  local task_id="$1" strategy="$2" deadline=$((SECONDS + MAX_WAIT)) events
  while (( SECONDS < deadline )); do
    events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$task_id/events")"
    jq -e 'any(.[]; .eventType=="TASK_DELIVERY_PAUSED_BEFORE_ACK")' <<<"$events" >/dev/null && return
    sleep 0.5
  done
  echo "$strategy consumer never reached the pre-acknowledgement crash window" >&2; return 1
}

run_case() {
  local strategy="$1" lower order order_id task task_id completed current events published_before_crash
  lower="$(tr '[:upper:]' '[:lower:]' <<<"$strategy")"
  put_policy "$strategy"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d "{\"productId\":1,\"quantity\":1,\"note\":\"$lower-unacked-redelivery\"}" "$BASE_URL/api/cart/items" >/dev/null
  order="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d '{}' "$BASE_URL/api/purchase-orders/checkout")"
  order_id="$(jq -r .id <<<"$order")"
  task="$(wait_for_task_field "$order_id" '.triggerPublishedAt != null' "$strategy task was not published before the crash experiment")"
  task_id="$(jq -r .id <<<"$task")"

  export SCHEDULING_MQ_CRASH_LAB_ENABLED=true SCHEDULING_MQ_CRASH_LAB_TASK_ID="$task_id"
  compose up -d --wait --force-recreate commerce-service commerce-portal-angular >/dev/null
  login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
  login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
  wait_for_pause_event "$task_id" "$strategy"
  events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$task_id/events")"
  published_before_crash="$(jq '[.[] | select(.eventType=="TASK_TRIGGER_PUBLISHED")] | length' <<<"$events")"
  (( published_before_crash >= 1 )) || { echo "$strategy has no broker publish acknowledgement" >&2; return 1; }

  compose stop -t 0 commerce-service >/dev/null
  export SCHEDULING_MQ_CRASH_LAB_ENABLED=false
  compose up -d --wait --force-recreate commerce-service commerce-portal-angular >/dev/null
  login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
  login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
  completed="$(wait_for_task_field "$order_id" '.status=="SUCCEEDED"' "$strategy did not redeliver and complete the task after commerce-service restart")"
  current="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/purchase-orders/$order_id")"
  events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$task_id/events")"
  for event_type in TASK_DELIVERY_PAUSED_BEFORE_ACK TASK_CLAIMED TASK_SUCCEEDED; do
    jq -e --arg event_type "$event_type" '([.[] | select(.eventType==$event_type)] | length)==1' <<<"$events" >/dev/null || {
      echo "$strategy expected one $event_type event" >&2; return 1;
    }
  done
  [[ "$(jq '[.[] | select(.eventType=="TASK_TRIGGER_PUBLISHED")] | length' <<<"$events")" == "$published_before_crash" ]] || {
    echo "$strategy was newly published instead of redelivered by the broker" >&2; return 1;
  }
  jq -e --arg strategy "$strategy" '.status=="SUCCEEDED" and .lastTriggerSource==$strategy and .attempts==1' <<<"$completed" >/dev/null
  [[ "$(jq -r .status <<<"$current")" == CANCELLED ]] || { echo "$strategy order was not cancelled" >&2; return 1; }
  jq -n --argjson order "$current" --argjson task "$completed" '{order:$order,task:$task}'
}

export ORDER_PAYMENT_TIMEOUT=20s SCHEDULING_RABBITMQ_ENABLED=true SCHEDULING_ROCKETMQ_ENABLED=true
export SCHEDULING_MQ_CRASH_LAB_ENABLED=false SCHEDULING_MQ_CRASH_LAB_PAUSE=5m
export SCHEDULING_DATABASE_INITIAL_DELAY=1h SCHEDULING_RECONCILIATION_INITIAL_DELAY=1h
compose up -d --wait rabbitmq-broker rocketmq-topic-init >/dev/null
compose up -d --wait --force-recreate commerce-service commerce-portal-angular >/dev/null
login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
ensure_address
curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/policies" |
  jq -c '.[] | select(.taskType=="PAYMENT_TIMEOUT")' > "$tmp/original-policy.json"
[[ -s "$tmp/original-policy.json" ]] || { echo 'PAYMENT_TIMEOUT policy was not found' >&2; exit 1; }

rabbit="$(run_case RABBITMQ)"
rocket="$(run_case ROCKETMQ)"
echo "MQ unacked redelivery verification passed: RabbitMQ and RocketMQ redelivered after commerce-service termination; each task was published, claimed and completed exactly once (tasks $(jq -r .task.id <<<"$rabbit")/$(jq -r .task.id <<<"$rocket"))."

#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:14200}"
METRICS_URL="${2:-http://localhost:18081}"
RABBIT_URL="${3:-http://localhost:15672}"
RABBIT_USER="${4:-${RABBITMQ_USERNAME:-commerceflow}}"
RABBIT_PASSWORD="${5:-${RABBITMQ_PASSWORD:-commerceflow-password}}"
MAX_WAIT="${6:-60}"
tmp="$(mktemp -d)"

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
  if [[ "$(jq -r .source "$tmp/original.json")" == DATABASE_OVERRIDE ]]; then
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -H 'Content-Type: application/json' \
      -X PUT -d "$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' "$tmp/original.json")" \
      "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  else
    curl -fsS -b "$tmp/admin.cookie" -H "$(cat "$tmp/admin.header")" -X DELETE \
      "$BASE_URL/api/admin/scheduling/policies/PAYMENT_TIMEOUT" >/dev/null
  fi
}

cleanup() {
  local status=$?
  if [[ -s "$tmp/original.json" ]]; then restore_policy || true; fi
  rm -rf "$tmp"
  exit "$status"
}
trap cleanup EXIT

ensure_address() {
  local selected address
  selected="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/addresses/selected")"
  [[ "$(jq -r '.addressId // empty' <<<"$selected")" != "" ]] && return
  address="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","addressLine2":"","postalCode":"200120","label":"Lab","isDefault":true}' "$BASE_URL/api/addresses")"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -X PUT -d '{}' "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" >/dev/null
}

new_expired_order_task() {
  local strategy="$1" lower order order_id deadline current task
  lower="$(tr '[:upper:]' '[:lower:]' <<<"$strategy")"
  curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d "{\"productId\":1,\"quantity\":1,\"note\":\"$lower-idempotency-acceptance\"}" "$BASE_URL/api/cart/items" >/dev/null
  order="$(curl -fsS -b "$tmp/customer.cookie" -H "$(cat "$tmp/customer.header")" -H 'Content-Type: application/json' \
    -d '{}' "$BASE_URL/api/purchase-orders/checkout")"
  order_id="$(jq -r .id <<<"$order")"; deadline=$((SECONDS + MAX_WAIT)); current="$order"
  while [[ "$(jq -r .status <<<"$current")" != CANCELLED && $SECONDS -lt $deadline ]]; do
    sleep 0.5; current="$(curl -fsS -b "$tmp/customer.cookie" "$BASE_URL/api/purchase-orders/$order_id")"
  done
  [[ "$(jq -r .status <<<"$current")" == CANCELLED ]] || { echo "$strategy did not close the order" >&2; return 1; }
  task="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks?taskType=PAYMENT_TIMEOUT&limit=500" |
    jq -c --arg id "$order_id" --arg strategy "$strategy" '.[] | select(.aggregateId==$id and .status=="SUCCEEDED" and .lastTriggerSource==$strategy)' | head -n1)"
  [[ -n "$task" ]] || { echo "$strategy task did not complete normally" >&2; return 1; }
  jq -n --argjson order "$order" --argjson task "$task" '{order:$order,task:$task}'
}

metric() {
  local name="$1"
  curl -fsS "$METRICS_URL/actuator/prometheus" | awk -v name="$name" '$1==name {print $2; found=1} END {if(!found) print 0}' | tail -n1
}

wait_metric_increase() {
  local name="$1" before="$2" increase="$3" deadline=$((SECONDS + 20)) current
  while (( SECONDS < deadline )); do
    current="$(metric "$name")"
    awk -v current="$current" -v target="$before" -v increase="$increase" 'BEGIN {exit !(current >= target + increase)}' && return
    sleep 0.5
  done
  echo "Metric $name did not increase by $increase" >&2; return 1
}

assert_single_execution() {
  local task_id="$1" events
  events="$(curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/tasks/$task_id/events")"
  jq -e '([.[] | select(.eventType=="TASK_CLAIMED")] | length)==1 and ([.[] | select(.eventType=="TASK_SUCCEEDED")] | length)==1' <<<"$events" >/dev/null
}

rabbit_api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" -H 'Content-Type: application/json' -X "$method" -d "$body" "$RABBIT_URL$path"
  else
    curl -fsS -u "$RABBIT_USER:$RABBIT_PASSWORD" -X "$method" "$RABBIT_URL$path"
  fi
}

login admin@example.com "$tmp/admin.cookie" "$tmp/admin.header"
login demo@example.com "$tmp/customer.cookie" "$tmp/customer.header"
curl -fsS -b "$tmp/admin.cookie" "$BASE_URL/api/admin/scheduling/policies" |
  jq -c '.[] | select(.taskType=="PAYMENT_TIMEOUT")' > "$tmp/original.json"
[[ -s "$tmp/original.json" ]] || { echo 'PAYMENT_TIMEOUT policy was not found' >&2; exit 1; }
ensure_address

put_policy RABBITMQ
rabbit_case="$(new_expired_order_task RABBITMQ)"; rabbit_task="$(jq -c .task <<<"$rabbit_case")"
rabbit_before="$(metric commerceflow_scheduled_tasks_rabbitmq_ignored_total)"
rabbit_payload="$(jq -c '{taskId:.id,taskType:.taskType,dueAt:.dueAt,strategySnapshot:"RABBITMQ"}' <<<"$rabbit_task")"
for number in 1 2; do
  publish_body="$(jq -n --arg payload "$rabbit_payload" '{properties:{content_type:"application/json",delivery_mode:2},routing_key:"scheduled-task.ready",payload:$payload,payload_encoding:"string"}')"
  [[ "$(rabbit_api POST /api/exchanges/%2F/commerceflow.scheduled-task.ready/publish "$publish_body" | jq -r .routed)" == true ]]
done
wait_metric_increase commerceflow_scheduled_tasks_rabbitmq_ignored_total "$rabbit_before" 2
assert_single_execution "$(jq -r .id <<<"$rabbit_task")"

dead_path='/api/queues/%2F/commerceflow.scheduled-task.dead'
[[ "$(rabbit_api GET "$dead_path" | jq -r .messages)" == 0 ]] || { echo 'RabbitMQ dead queue must be empty before the poison-message experiment' >&2; exit 1; }
rabbit_poison="rabbit-poison-$(date +%s%N)"
poison_body="$(jq -n --arg payload "{invalid-json-$rabbit_poison" --arg id "$rabbit_poison" '{properties:{content_type:"application/json",delivery_mode:2,message_id:$id},routing_key:"scheduled-task.ready",payload:$payload,payload_encoding:"string"}')"
[[ "$(rabbit_api POST /api/exchanges/%2F/commerceflow.scheduled-task.ready/publish "$poison_body" | jq -r .routed)" == true ]]
deadline=$((SECONDS + 20))
while (( SECONDS < deadline )) && [[ "$(rabbit_api GET "$dead_path" | jq -r .messages)" -lt 1 ]]; do sleep 0.5; done
[[ "$(rabbit_api GET "$dead_path" | jq -r .messages)" == 1 ]] || { echo 'RabbitMQ poison message did not enter the dead queue' >&2; exit 1; }
dead_message="$(rabbit_api POST "$dead_path/get" '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto","truncate":50000}')"
jq -e --arg marker "$rabbit_poison" 'length==1 and (.[0].payload | contains($marker))' <<<"$dead_message" >/dev/null

put_policy ROCKETMQ
rocket_case="$(new_expired_order_task ROCKETMQ)"; rocket_task="$(jq -c .task <<<"$rocket_case")"
rocket_before="$(metric commerceflow_scheduled_tasks_rocketmq_ignored_total)"
rocket_payload="$(jq -c '{taskId:.id,taskType:.taskType,dueAt:.dueAt,strategySnapshot:"ROCKETMQ"}' <<<"$rocket_task")"
for number in 1 2; do
  key="rocket-duplicate-$(jq -r .id <<<"$rocket_task")-$number-$(date +%s%N)"
  docker compose exec -T rocketmq-broker sh mqadmin sendMessage -n rocketmq-nameserver:9876 \
    -t commerceflow-scheduled-task -c SCHEDULED_TASK -k "$key" -p "$rocket_payload" >/dev/null
done
wait_metric_increase commerceflow_scheduled_tasks_rocketmq_ignored_total "$rocket_before" 2
assert_single_execution "$(jq -r .id <<<"$rocket_task")"

group_config="$(docker compose exec -T rocketmq-broker sh mqadmin getConsumerConfig -n rocketmq-nameserver:9876 -g commerceflow-scheduled-task-consumer)"
grep -Eq 'retryMaxTimes[[:space:]]*=[[:space:]]*2' <<<"$group_config" || { echo 'RocketMQ acceptance consumer group must use retryMaxTimes=2' >&2; exit 1; }
rocket_poison="rocket-poison-$(date +%s%N)"
docker compose exec -T rocketmq-broker sh mqadmin sendMessage -n rocketmq-nameserver:9876 \
  -t commerceflow-scheduled-task -c SCHEDULED_TASK -k "$rocket_poison" -p "{invalid-json-$rocket_poison" >/dev/null
deadline=$((SECONDS + 30)); query=''
while (( SECONDS < deadline )); do
  sleep 1
  query="$(docker compose exec -T rocketmq-broker sh mqadmin queryMsgByKey -n rocketmq-nameserver:9876 -t '%DLQ%commerceflow-scheduled-task-consumer' -k "$rocket_poison" 2>&1 || true)"
  grep -Fq "$rocket_poison" <<<"$query" && break
done
grep -Fq "$rocket_poison" <<<"$query" || { echo 'RocketMQ poison message did not enter the DLQ' >&2; exit 1; }

echo "MQ scheduling idempotency verification passed: RabbitMQ and RocketMQ duplicate/late messages executed once; poison messages reached their DLQs (tasks $(jq -r .id <<<"$rabbit_task")/$(jq -r .id <<<"$rocket_task"))."

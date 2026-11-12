#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
MAX_WAIT_SECONDS="${2:-120}"
temp_dir="$(new_temp_dir)"
admin_jar="$temp_dir/admin.cookies"
customer_jar="$temp_dir/customer.cookies"
original_receive=''
original_complete=''

env_names=(
  SCHEDULING_RABBITMQ_ENABLED
  SCHEDULING_ROCKETMQ_ENABLED
  SCHEDULING_RABBITMQ_PUBLISH_FIXED_DELAY
  SCHEDULING_ROCKETMQ_PUBLISH_FIXED_DELAY
)
declare -A saved_values saved_present
for name in "${env_names[@]}"; do
  if [[ -v "$name" ]]; then saved_present[$name]=true; saved_values[$name]="${!name}"; else saved_present[$name]=false; fi
done

sql() {
  compose exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password --batch --skip-column-names commerceflow -e "$1" 2>/dev/null | tail -n1 | tr -d '\r'
}

wait_sql_nonempty() {
  local query="$1" label="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) value=''
  while (( SECONDS < deadline )); do
    value="$(sql "$query")"
    [[ -n "$value" ]] && { printf '%s' "$value"; return; }
    sleep 0.5
  done
  echo "$label timed out (last=$value)" >&2
  return 1
}

wait_sql_equals() {
  local query="$1" expected="$2" label="$3" deadline=$((SECONDS + MAX_WAIT_SECONDS)) value=''
  while (( SECONDS < deadline )); do
    value="$(sql "$query")"
    [[ "$value" == "$expected" ]] && return
    sleep 0.5
  done
  echo "$label timed out (last=$value, expected=$expected)" >&2
  return 1
}

put_policy() {
  local type="$1" body="$2"
  json_request PUT "$BASE_URL/api/admin/scheduling/policies/$type" "$admin_jar" "$admin_header" "$admin_token" "$body" >/dev/null
}

restore_policy() {
  local type="$1" original="$2"
  [[ -n "$original" ]] || return
  if [[ "$(jq -r .source <<<"$original")" == DATABASE_OVERRIDE ]]; then
    put_policy "$type" "$(jq '{enabled,primaryTrigger,reconciliationEnabled,gracePeriodSeconds,claimLeaseSeconds,maxAttempts,retryDelaySeconds}' <<<"$original")" || true
  else
    json_request DELETE "$BASE_URL/api/admin/scheduling/policies/$type" "$admin_jar" "$admin_header" "$admin_token" >/dev/null || true
  fi
}

broker() {
  local trigger="$1" action="$2" profile service
  if [[ "$trigger" == RABBITMQ ]]; then profile=rabbitmq; service=rabbitmq-broker; else profile=rocketmq; service=rocketmq-broker; fi
  if [[ "$action" == stop ]]; then compose --profile "$profile" stop "$service"; else compose --profile "$profile" up -d --wait "$service"; fi
}

new_delivered_order() {
  local before id
  before="$(sql "SELECT COALESCE(MAX(id),0) FROM shop_orders")"
  bash "$SCRIPT_DIR/verify-logistics.sh" "$BASE_URL" >&2 || return
  id="$(sql "SELECT o.id FROM shop_orders o JOIN shipment_records s ON s.order_id=o.id JOIN app_users u ON u.id=o.customer_id WHERE u.email='demo@example.com' AND o.id>$before AND o.status='SHIPPED' AND s.status='DELIVERED' ORDER BY o.id DESC LIMIT 1")"
  [[ -n "$id" ]] || { echo 'Delivered order not found' >&2; return 1; }
  printf '%s' "$id"
}

assert_publish_failure() {
  local task_id="$1" label="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) attempts=0 error=''
  while (( SECONDS < deadline )); do
    attempts="$(sql "SELECT trigger_publish_attempts FROM scheduled_tasks WHERE id=$task_id")"
    error="$(sql "SELECT COALESCE(trigger_last_error,'') FROM scheduled_tasks WHERE id=$task_id")"
    if (( attempts >= 1 )) && [[ -n "$error" ]]; then return; fi
    sleep 0.5
  done
  echo "$label did not record broker publish failure (attempts=$attempts, error=$error)" >&2
  return 1
}

verify_task() {
  local task_id="$1" trigger="$2" expected_order_status="$3" order_id="$4" audit count
  wait_sql_equals "SELECT status FROM shop_orders WHERE id=$order_id" "$expected_order_status" "$trigger order $expected_order_status"
  audit="$(sql "SELECT CONCAT(status,':',last_trigger_source,':',primary_trigger_snapshot) FROM scheduled_tasks WHERE id=$task_id")"
  [[ "$audit" == "SUCCEEDED:$trigger:$trigger" ]] || { echo "Unexpected $trigger task audit: $audit" >&2; return 1; }
  count="$(sql "SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$task_id AND event_type='TASK_SUCCEEDED'")"
  [[ "$count" == 1 ]] || { echo "$trigger task succeeded $count times" >&2; return 1; }
}

cleanup() {
  local status=$?
  set +e
  if [[ -n "${admin_header:-}" ]]; then
    restore_policy AUTO_RECEIVE "$original_receive"
    restore_policy AUTO_COMPLETE "$original_complete"
  fi
  for name in "${env_names[@]}"; do
    if [[ "${saved_present[$name]}" == true ]]; then printf -v "$name" '%s' "${saved_values[$name]}"; export "$name"; else unset "$name"; fi
  done
  compose --profile rabbitmq --profile rocketmq up -d --wait rabbitmq-broker rocketmq-broker >/dev/null
  compose up -d --no-deps --force-recreate commerce-service commerce-portal-angular >/dev/null
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

compose --profile rabbitmq --profile rocketmq up -d --wait rabbitmq-broker rocketmq-broker
export SCHEDULING_RABBITMQ_ENABLED=true
export SCHEDULING_ROCKETMQ_ENABLED=true
export SCHEDULING_RABBITMQ_PUBLISH_FIXED_DELAY=1s
export SCHEDULING_ROCKETMQ_PUBLISH_FIXED_DELAY=1s
compose --profile rabbitmq --profile rocketmq up -d --no-deps --force-recreate commerce-service commerce-portal-angular

deadline=$((SECONDS + 60))
until curl -fsS "$BASE_URL/api/products" >/dev/null; do
  (( SECONDS < deadline )) || { echo 'Storefront did not become ready' >&2; exit 1; }
  sleep 1
done

IFS=$'\t' read -r admin_header admin_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
policies="$(json_request GET "$BASE_URL/api/admin/scheduling/policies" "$admin_jar")"
original_receive="$(jq -c '.[] | select(.taskType=="AUTO_RECEIVE")' <<<"$policies")"
original_complete="$(jq -c '.[] | select(.taskType=="AUTO_COMPLETE")' <<<"$policies")"
[[ -n "$original_receive" && -n "$original_complete" ]] || { echo 'AUTO lifecycle policies were not found' >&2; exit 1; }

policy_body() {
  jq -nc --arg trigger "$1" '{enabled:true,primaryTrigger:$trigger,reconciliationEnabled:false,gracePeriodSeconds:0,claimLeaseSeconds:30,maxAttempts:8,retryDelaySeconds:1}'
}

for trigger in RABBITMQ ROCKETMQ; do
  put_policy AUTO_RECEIVE "$(policy_body "$trigger")"
  put_policy AUTO_COMPLETE "$(policy_body "$trigger")"

  broker "$trigger" stop
  receive_order="$(new_delivered_order)"
  receive_task="$(wait_sql_nonempty "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$receive_order'" "$trigger AUTO_RECEIVE creation")"
  [[ "$(sql "SELECT primary_trigger_snapshot FROM scheduled_tasks WHERE id=$receive_task")" == "$trigger" ]] || { echo "AUTO_RECEIVE snapshot is not $trigger" >&2; exit 1; }
  assert_publish_failure "$receive_task" "$trigger AUTO_RECEIVE"
  sql "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=$receive_task" >/dev/null
  broker "$trigger" start
  verify_task "$receive_task" "$trigger" RECEIVED "$receive_order"

  broker "$trigger" stop
  complete_order="$(new_delivered_order)"
  wait_sql_nonempty "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$complete_order'" "$trigger AUTO_RECEIVE fixture creation" >/dev/null
  received="$(json_request POST "$BASE_URL/api/purchase-orders/$complete_order/receive" "$customer_jar" "$customer_header" "$customer_token" '{}')"
  [[ "$(jq -r .status <<<"$received")" == RECEIVED ]] || { echo 'Manual receipt fixture failed' >&2; exit 1; }
  [[ "$(sql "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$complete_order'")" == CANCELLED ]] || { echo 'Manual receipt did not cancel AUTO_RECEIVE' >&2; exit 1; }
  complete_task="$(wait_sql_nonempty "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$complete_order'" "$trigger AUTO_COMPLETE creation")"
  [[ "$(sql "SELECT primary_trigger_snapshot FROM scheduled_tasks WHERE id=$complete_task")" == "$trigger" ]] || { echo "AUTO_COMPLETE snapshot is not $trigger" >&2; exit 1; }
  assert_publish_failure "$complete_task" "$trigger AUTO_COMPLETE"
  sql "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=$complete_task" >/dev/null
  broker "$trigger" start
  verify_task "$complete_task" "$trigger" COMPLETED "$complete_order"
done

echo 'AUTO_RECEIVE/AUTO_COMPLETE MQ failure verification passed for RabbitMQ and RocketMQ: publish failure, broker recovery, snapshot execution and single side effect.'

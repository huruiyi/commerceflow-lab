#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
RABBIT_URL="${2:-http://localhost:15672}"
DURATION_SECONDS="${3:-120}"
MINIMUM_ROUNDS="${4:-6}"
CONCURRENCY="${5:-12}"
REQUESTS_PER_WORKER="${6:-4}"
MAX_WAIT_SECONDS="${7:-90}"
(( DURATION_SECONDS >= 30 && MINIMUM_ROUNDS >= 2 && CONCURRENCY >= 4 && CONCURRENCY <= 64 && REQUESTS_PER_WORKER >= 1 )) || { echo 'Invalid stress parameters' >&2; exit 2; }

temp_dir="$(new_temp_dir)"
admin_jar="$temp_dir/admin.cookies"
customer_jar="$temp_dir/customer.cookies"
original_receive=''
original_complete=''
if [[ -v SCHEDULING_RABBITMQ_ENABLED ]]; then saved_rabbit_present=true; saved_rabbit="$SCHEDULING_RABBITMQ_ENABLED"; else saved_rabbit_present=false; saved_rabbit=''; fi

sql() {
  compose exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse "$1" commerceflow 2>/dev/null | tail -n1 | tr -d '\r'
}

wait_sql_nonempty() {
  local query="$1" label="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) value=''
  while (( SECONDS < deadline )); do value="$(sql "$query")"; [[ -n "$value" ]] && { printf '%s' "$value"; return; }; sleep 0.2; done
  echo "$label timed out (last=$value)" >&2; return 1
}

wait_sql_status() {
  local order_id="$1" expected="$2" label="$3" deadline=$((SECONDS + MAX_WAIT_SECONDS)) value=''
  while (( SECONDS < deadline )); do value="$(sql "SELECT status FROM shop_orders WHERE id=$order_id")"; [[ "$value" == "$expected" ]] && return; sleep 0.2; done
  echo "$label timed out (last=$value)" >&2; return 1
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

cleanup() {
  local status=$?
  set +e
  if [[ -n "${admin_header:-}" ]]; then restore_policy AUTO_RECEIVE "$original_receive"; restore_policy AUTO_COMPLETE "$original_complete"; fi
  if [[ "$saved_rabbit_present" == true ]]; then export SCHEDULING_RABBITMQ_ENABLED="$saved_rabbit"; else unset SCHEDULING_RABBITMQ_ENABLED; fi
  compose up -d --no-deps --force-recreate commerce-service commerce-portal-angular >/dev/null
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

new_delivered_order() {
  local before id
  before="$(sql 'SELECT COALESCE(MAX(id),0) FROM shop_orders')"
  bash "$SCRIPT_DIR/verify-logistics.sh" "$BASE_URL" >&2 || return
  id="$(wait_sql_nonempty "SELECT o.id FROM shop_orders o JOIN shipment_records s ON s.order_id=o.id WHERE o.id>$before AND o.status='SHIPPED' AND s.status='DELIVERED' ORDER BY o.id DESC LIMIT 1" 'delivered order creation')"
  printf '%s' "$id"
}

http_worker() {
  local order_id="$1" output="$2" index code
  for ((index=0; index<REQUESTS_PER_WORKER; index++)); do
    code="$(curl -sS -o /dev/null -w '%{http_code}' -b "$customer_jar" -H "$customer_header: $customer_token" -H 'Content-Type: application/json' -X POST -d '{}' "$BASE_URL/api/purchase-orders/$order_id/receive" || printf 000)"
    printf 'HTTP %s\n' "$code" >>"$output"
  done
}

rabbit_worker() {
  local publish_body="$1" output="$2" index routed
  for ((index=0; index<REQUESTS_PER_WORKER; index++)); do
    routed="$(curl -fsS -u commerceflow:commerceflow-password -H 'Content-Type: application/json' -X POST -d "$publish_body" "$RABBIT_URL/api/exchanges/%2F/commerceflow.scheduled-task.ready/publish" | jq -r .routed 2>/dev/null || printf false)"
    [[ "$routed" == true ]] && printf 'RABBIT 200\n' >>"$output" || printf 'RABBIT 500\n' >>"$output"
  done
}

invoke_race() {
  local order_id="$1" task_id="$2" task_type="$3" bias="$4" payload publish_body worker half=$((CONCURRENCY / 2)) output
  output="$temp_dir/race-$task_id.txt"
  : >"$output"
  sql "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=$task_id" >/dev/null
  payload="$(jq -nc --argjson id "$task_id" --arg type "$task_type" '{taskId:$id,taskType:$type,dueAt:"2000-01-01T00:00:00Z",strategySnapshot:"RABBITMQ"}')"
  publish_body="$(jq -nc --arg payload "$payload" '{properties:{content_type:"application/json",delivery_mode:2},routing_key:"scheduled-task.ready",payload:$payload,payload_encoding:"string"}')"
  if [[ "$bias" == MANUAL ]]; then
    for ((worker=0; worker<half; worker++)); do http_worker "$order_id" "$output" & done
    sleep 0.1
    for ((worker=0; worker<half; worker++)); do rabbit_worker "$publish_body" "$output" & done
  else
    for ((worker=0; worker<half; worker++)); do rabbit_worker "$publish_body" "$output" & done
    sleep 0.1
    for ((worker=0; worker<half; worker++)); do http_worker "$order_id" "$output" & done
  fi
  wait
  local rabbit_failures http_success http_unexpected
  rabbit_failures="$(awk '$1=="RABBIT" && $2!=200{count++} END{print count+0}' "$output")"
  http_success="$(awk '$1=="HTTP" && $2==200{count++} END{print count+0}' "$output")"
  http_unexpected="$(awk '$1=="HTTP" && $2!=200 && $2!=503{count++} END{print count+0}' "$output")"
  (( rabbit_failures == 0 && http_success >= 1 && http_unexpected == 0 )) || { echo "$task_type race request failure: rabbit=$rabbit_failures httpSuccess=$http_success httpUnexpected=$http_unexpected" >&2; return 1; }
  wc -l <"$output" | tr -d ' '
}

compose --profile rabbitmq up -d --wait rabbitmq-broker
export SCHEDULING_RABBITMQ_ENABLED=true
compose --profile rabbitmq up -d --no-deps --force-recreate commerce-service commerce-portal-angular
deadline_ready=$((SECONDS + 60))
until curl -fsS "$BASE_URL/api/products" >/dev/null; do (( SECONDS < deadline_ready )) || { echo 'Storefront did not become ready' >&2; exit 1; }; sleep 1; done

IFS=$'\t' read -r admin_header admin_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
policies="$(json_request GET "$BASE_URL/api/admin/scheduling/policies" "$admin_jar")"
original_receive="$(jq -c '.[]|select(.taskType=="AUTO_RECEIVE")' <<<"$policies")"
original_complete="$(jq -c '.[]|select(.taskType=="AUTO_COMPLETE")' <<<"$policies")"
[[ -n "$original_receive" && -n "$original_complete" ]] || { echo 'AUTO lifecycle policies were not found' >&2; exit 1; }
policy='{"enabled":true,"primaryTrigger":"RABBITMQ","reconciliationEnabled":false,"gracePeriodSeconds":0,"claimLeaseSeconds":30,"maxAttempts":8,"retryDelaySeconds":1}'
put_policy AUTO_RECEIVE "$policy"
put_policy AUTO_COMPLETE "$policy"

deadline=$((SECONDS + DURATION_SECONDS)); rounds=0; total_requests=0; manual_wins=0; auto_wins=0
while (( rounds < MINIMUM_ROUNDS || SECONDS < deadline )); do
  rounds=$((rounds + 1)); if (( rounds % 2 == 1 )); then bias=MANUAL; else bias=AUTO; fi
  order_id="$(new_delivered_order)"
  receive_task="$(wait_sql_nonempty "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$order_id'" "round $rounds AUTO_RECEIVE creation")"
  race_count="$(invoke_race "$order_id" "$receive_task" AUTO_RECEIVE "$bias")" || exit
  total_requests=$((total_requests + race_count))
  receipt_status="$(sql "SELECT status FROM shop_orders WHERE id=$order_id")"
  [[ "$receipt_status" == RECEIVED || "$receipt_status" == COMPLETED ]] || wait_sql_status "$order_id" RECEIVED "round $rounds receipt"
  receipt_events="$(sql "SELECT COUNT(*) FROM order_events WHERE order_id=$order_id AND event_type IN ('ORDER_RECEIVED','ORDER_AUTO_RECEIVED')")"
  [[ "$receipt_events" == 1 ]] || { echo "Round $rounds produced $receipt_events receipt transitions" >&2; exit 1; }
  winner="$(sql "SELECT event_type FROM order_events WHERE order_id=$order_id AND event_type IN ('ORDER_RECEIVED','ORDER_AUTO_RECEIVED') LIMIT 1")"
  if [[ "$winner" == ORDER_RECEIVED ]]; then manual_wins=$((manual_wins + 1)); else auto_wins=$((auto_wins + 1)); fi
  complete_task="$(wait_sql_nonempty "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$order_id'" "round $rounds AUTO_COMPLETE creation")"
  race_count="$(invoke_race "$order_id" "$complete_task" AUTO_COMPLETE "$bias")" || exit
  total_requests=$((total_requests + race_count))
  wait_sql_status "$order_id" COMPLETED "round $rounds completion"
  audit="$(sql "SELECT CONCAT((SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$order_id' AND task_type='AUTO_RECEIVE'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$order_id' AND task_type='AUTO_COMPLETE'),'|',(SELECT COUNT(*) FROM order_events WHERE order_id=$order_id AND event_type='ORDER_AUTO_COMPLETED'),'|',(SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$complete_task AND event_type='TASK_CLAIMED'),'|',(SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$complete_task AND event_type='TASK_SUCCEEDED'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_key='order:$order_id' AND status='FAILED'))")"
  [[ "$audit" == '1|1|1|1|1|0' ]] || { echo "Round $rounds invariant failure: $audit" >&2; exit 1; }
done
(( manual_wins >= 1 && auto_wins >= 1 )) || { echo "Both receipt outcomes were not covered (manual=$manual_wins auto=$auto_wins)" >&2; exit 1; }
echo "Receipt lifecycle soak passed: duration=$DURATION_SECONDS seconds, rounds=$rounds, concurrency=$CONCURRENCY, requests=$total_requests, manualWins=$manual_wins, autoWins=$auto_wins, no duplicate receipt/completion side effects."

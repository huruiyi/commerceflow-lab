#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
db_user="${DATABASE_USERNAME:-commerceflow}"
db_password="${DATABASE_PASSWORD:-commerceflow-password}"

db_scalar(){ compose exec -T commerce-db mysql --batch --skip-column-names "-u$db_user" "-p$db_password" commerceflow -e "$1" | tail -n 1 | tr -d '\r'; }
wait_order_status(){
  local id="$1" expected="$2" deadline=$((SECONDS+40)) order
  while ((SECONDS<deadline));do
    order="$(json_request GET "$BASE_URL/api/purchase-orders/$id" "$customer_jar")"
    [[ "$(jq -r .status <<<"$order")" == "$expected" ]]&&{ printf '%s' "$order";return; }
    sleep 2
  done
  echo "Order $id did not reach $expected" >&2;return 1
}
wait_task_status(){
  local id="$1" expected="$2" deadline=$((SECONDS+90)) status
  while ((SECONDS<deadline));do
    status="$(db_scalar "SELECT status FROM scheduled_tasks WHERE id=$id")"
    [[ "$status" == "$expected" ]]&&return
    sleep 2
  done
  echo "Scheduled task $id did not reach $expected (last=$status)" >&2;return 1
}

"$SCRIPT_DIR/verify-logistics.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)";trap 'rm -rf -- "$temp_dir"' EXIT
customer_jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
manual="$(jq -c '[.[]|select(.status=="SHIPPED" and .logistics.status=="DELIVERED")][0]'<<<"$orders")"
manual_id="$(jq -r '.id//empty'<<<"$manual")";[[ -n "$manual_id" ]]||{ echo 'Delivered order for manual receipt was not found'>&2;exit 1; }
received="$(json_request POST "$BASE_URL/api/purchase-orders/$manual_id/receive" "$customer_jar" "$csrf_header" "$csrf_token" '{}')"
jq -e '.status=="RECEIVED" and .receivedAt!=null'<<<"$received">/dev/null||{ echo 'Manual SHIPPED -> RECEIVED transition failed'>&2;exit 1; }
[[ "$(db_scalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$manual_id'")" == CANCELLED ]]||{ echo 'Manual receipt did not cancel AUTO_RECEIVE'>&2;exit 1; }
[[ "$(db_scalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$manual_id'")" == SCHEDULED ]]||{ echo 'Manual receipt did not schedule AUTO_COMPLETE'>&2;exit 1; }

"$SCRIPT_DIR/verify-logistics.sh" "$BASE_URL"
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
automatic="$(jq -c '[.[]|select(.status=="SHIPPED" and .logistics.status=="DELIVERED")][0]'<<<"$orders")"
automatic_id="$(jq -r '.id//empty'<<<"$automatic")";[[ -n "$automatic_id" ]]||{ echo 'Delivered order for automatic receipt was not found'>&2;exit 1; }
db_scalar "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE task_type='AUTO_RECEIVE' AND task_key='order:$automatic_id'" >/dev/null
auto_received="$(wait_order_status "$automatic_id" RECEIVED)"
jq -e '.receivedAt!=null'<<<"$auto_received">/dev/null||{ echo 'AUTO_RECEIVE did not persist receivedAt'>&2;exit 1; }
[[ "$(db_scalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$automatic_id'")" == SUCCEEDED ]]||{ echo 'AUTO_RECEIVE task did not succeed'>&2;exit 1; }

db_scalar "UPDATE scheduled_tasks SET due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE task_type='AUTO_COMPLETE' AND task_key='order:$automatic_id'" >/dev/null
completed="$(wait_order_status "$automatic_id" COMPLETED)"
jq -e '.completedAt!=null and .payment!=null and .logistics.status=="DELIVERED" and (.availableActions|index("REVIEW_ITEM")!=null)'<<<"$completed">/dev/null||{ echo 'COMPLETED order lost completion time, payment, logistics or review action'>&2;exit 1; }
[[ "$(db_scalar "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$automatic_id'")" == SUCCEEDED ]]||{ echo 'AUTO_COMPLETE task did not succeed'>&2;exit 1; }
[[ "$(db_scalar "SELECT COUNT(*) FROM order_events WHERE order_id=$automatic_id AND event_type IN ('ORDER_AUTO_RECEIVED','ORDER_AUTO_COMPLETED')")" == 2 ]]||{ echo 'Automatic lifecycle event count is invalid'>&2;exit 1; }
item_id="$(jq -r '.items[0].id'<<<"$completed")"
review="$(json_request POST "$BASE_URL/api/order-items/$item_id/reviews" "$customer_jar" "$csrf_header" "$csrf_token" '{"rating":5,"comment":"Completed order remains reviewable"}')"
[[ "$(jq -r .rating<<<"$review")" == 5 ]]||{ echo 'COMPLETED order could not be reviewed'>&2;exit 1; }

# Recreate the crash window after the order transition committed but before task acknowledgement.
complete_task_id="$(db_scalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND task_key='order:$automatic_id'")"
compose stop commerce-service >/dev/null
restart_required=true
restart_backend(){
  if [[ "${restart_required:-false}" == true ]];then compose up -d --wait commerce-service >/dev/null;fi
}
trap 'restart_backend; rm -rf -- "$temp_dir"' EXIT
db_scalar "UPDATE scheduled_tasks SET status='PROCESSING',completed_at=NULL,due_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 SECOND),lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),claimed_by='simulated-crashed-worker',last_error=NULL WHERE id=$complete_task_id" >/dev/null
compose up -d --wait commerce-service >/dev/null
restart_required=false
wait_task_status "$complete_task_id" SUCCEEDED
lease_events="$(db_scalar "SELECT COUNT(*) FROM scheduled_task_events WHERE task_id=$complete_task_id AND event_type='TASK_LEASE_EXPIRED'")"
completion_events="$(db_scalar "SELECT COUNT(*) FROM order_events WHERE order_id=$automatic_id AND event_type='ORDER_AUTO_COMPLETED'")"
((lease_events>=1&&completion_events==1))||{ echo "Crash recovery was not idempotent (leaseEvents=$lease_events, completionEvents=$completion_events)" >&2;exit 1; }
echo "Automatic lifecycle verification passed: manual receipt cancels AUTO_RECEIVE; DELIVERED -> AUTO_RECEIVE -> RECEIVED -> AUTO_COMPLETE -> COMPLETED; expired lease recovered after commerce-service restart without duplicate completion; payment/logistics/review remain available (order=$(jq -r .orderNumber<<<"$completed"))"

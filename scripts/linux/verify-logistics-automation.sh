#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq

BASE_URL="${1:-http://localhost:14200}"
variables=(LOGISTICS_AUTO_PROGRESS_ENABLED LOGISTICS_AUTO_PROGRESS_INITIAL_DELAY LOGISTICS_AUTO_PROGRESS_SCAN_INTERVAL LOGISTICS_AUTO_PROGRESS_STEP_INTERVAL LOGISTICS_AUTO_PROGRESS_BATCH_SIZE)
declare -A previous_values previous_set
for name in "${variables[@]}";do
  [[ -v "$name" ]]&&previous_set[$name]=true||previous_set[$name]=false
  previous_values[$name]="${!name-}"
done
restore_automation(){
  for name in "${variables[@]}";do
    if [[ "${previous_set[$name]}" == true ]];then export "$name=${previous_values[$name]}";else unset "$name";fi
  done
  compose up -d --force-recreate --wait logistics-simulator-service >/dev/null
}
trap restore_automation EXIT

export LOGISTICS_AUTO_PROGRESS_ENABLED=true
export LOGISTICS_AUTO_PROGRESS_INITIAL_DELAY=2s
export LOGISTICS_AUTO_PROGRESS_SCAN_INTERVAL=1s
export LOGISTICS_AUTO_PROGRESS_STEP_INTERVAL=1s
export LOGISTICS_AUTO_PROGRESS_BATCH_SIZE=100
compose up -d --force-recreate --wait logistics-simulator-service >/dev/null

"$SCRIPT_DIR/verify-merchant-fulfillment.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"
trap 'rm -rf -- "$temp_dir"; restore_automation' EXIT
customer_jar="$temp_dir/customer.cookies"
storefront_login "$BASE_URL" demo@example.com "$customer_jar" >/dev/null
orders="$(json_request GET "$BASE_URL/api/purchase-orders" "$customer_jar")"
candidate="$(jq -c '[.[]|select(.status=="SHIPPED" and .logistics!=null)][0]'<<<"$orders")"
order_id="$(jq -r '.id//empty'<<<"$candidate")";[[ -n "$order_id" ]]||{ echo 'Automatic logistics shipment was not visible to customer'>&2;exit 1; }
deadline=$((SECONDS+40));delivered=''
while ((SECONDS<deadline));do
  delivered="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$customer_jar")"
  [[ "$(jq -r '.logistics.status'<<<"$delivered")" == DELIVERED ]]&&break
  sleep 1
done
jq -e '.logistics.status=="DELIVERED" and (.logistics.events|length)==6 and ([.logistics.events[]|select(.eventType!="PROGRESS")]|length)==0'<<<"$delivered">/dev/null||{ echo 'Automatic logistics timeline is incomplete'>&2;exit 1; }
db_user="${DATABASE_USERNAME:-commerceflow}";db_password="${DATABASE_PASSWORD:-commerceflow-password}"
task_status="$(compose exec -T commerce-db mysql --batch --skip-column-names "-u$db_user" "-p$db_password" commerceflow -e "SELECT status FROM scheduled_tasks WHERE task_type='AUTO_RECEIVE' AND task_key='order:$order_id'"|tail -n1|tr -d '\r')"
[[ "$task_status" == SCHEDULED ]]||{ echo 'Automatic DELIVERED callback did not schedule AUTO_RECEIVE'>&2;exit 1; }
echo "Automatic logistics verification passed: configuration-driven CREATED -> DELIVERED and AUTO_RECEIVE scheduling (order=$(jq -r .orderNumber<<<"$candidate"))"

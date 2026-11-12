#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq

MAX_WAIT=180;SKIP_MIGRATION=false
while (($#));do
  case "$1" in
    --max-wait-seconds) MAX_WAIT="$2";shift 2;;
    --skip-migration-rehearsal) SKIP_MIGRATION=true;shift;;
    *) echo "Unknown argument: $1" >&2;exit 2;;
  esac
done
db_user="${DATABASE_USERNAME:-commerceflow}";db_password="${DATABASE_PASSWORD:-commerceflow-password}"
suffix="$(date +%s)-$$-$RANDOM";passed=false
compose_all(){ docker compose --project-directory "$PROJECT_ROOT" --profile rabbitmq --profile rocketmq "$@"; }
db_scalar(){ compose exec -T commerce-db mysql --batch --skip-column-names "-u$db_user" "-p$db_password" commerceflow -e "$1" 2>/dev/null|tail -n1|tr -d '\r'; }
service_db_scalar(){ compose exec -T "$1" commerce-db --batch --skip-column-names "-u$3" "-p$4" "$2" -e "$5" 2>/dev/null|tail -n1|tr -d '\r'; }
history_fingerprint(){
  db_scalar "SELECT CONCAT_WS('|',(SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_number,customer_id,status,total_amount,currency,COALESCE(confirmed_at,''),COALESCE(shipped_at,''),COALESCE(carrier,''),COALESCE(tracking_number,''),payment_due_at,COALESCE(cancelled_at,''),COALESCE(cancel_reason,''),COALESCE(received_at,''),COALESCE(completed_at,''),COALESCE(logistics_delivered_at,'')))),0)) FROM shop_orders WHERE status IN ('CANCELLED','COMPLETED','REFUNDED')),(SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,payment_number,amount,status,channel,currency,COALESCE(channel_transaction_no,''),COALESCE(paid_at,''),COALESCE(expires_at,''),refunded_amount))),0)) FROM payment_records WHERE status IN ('SUCCESS','FAILED','CLOSED','EXPIRED')),(SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_id,merchant_shipment_no,COALESCE(platform_shipment_no,''),carrier,tracking_number,status,COALESCE(shipped_at,''),COALESCE(delivered_at,'')))),0)) FROM shipment_records WHERE status='DELIVERED'),(SELECT CONCAT(COUNT(*),':',COALESCE(BIT_XOR(CRC32(CONCAT_WS('#',id,order_item_id,product_id,customer_id,rating,comment,created_at))),0)) FROM product_reviews))"
}
wait_task(){
  local id="$1" deadline=$((SECONDS+MAX_WAIT)) last=''
  while ((SECONDS<deadline));do last="$(db_scalar "SELECT status FROM scheduled_tasks WHERE id=$id")";[[ "$last" == SUCCEEDED ]]&&return;sleep 2;done
  echo "Task $id did not recover (last=$last)" >&2;return 1
}
cleanup(){
  local status=$?;set +e
  unset SCHEDULING_RABBITMQ_ENABLED SCHEDULING_ROCKETMQ_ENABLED SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY SCHEDULING_LEASE_RECOVERY_FIXED_DELAY SCHEDULING_DATABASE_INITIAL_DELAY SCHEDULING_DATABASE_FIXED_DELAY
  compose up -d --wait --force-recreate commerce-service commerce-portal-angular payment-simulator-web kafka-console >/dev/null
  if [[ "$passed" != true ]];then compose_all logs --no-color --tail 150 commerce-service rabbitmq-broker rocketmq-broker;fi
  exit "$status"
}
trap cleanup EXIT

[[ "$SKIP_MIGRATION" == true ]]||"$SCRIPT_DIR/verify-flyway-lifecycle.sh"
compose_all up -d --wait commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker payment-simulator-service logistics-simulator-service rabbitmq-broker rocketmq-nameserver rocketmq-broker commerce-service commerce-portal-angular payment-simulator-web kafka-console
versions="$(db_scalar "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")|$(service_db_scalar payment-simulator-db payment_platform payment payment-password "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")|$(service_db_scalar logistics-simulator-db logistics_platform logistics logistics-password "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)),'|',SUM(success=0)) FROM flyway_schema_history WHERE version IS NOT NULL")"
[[ "$versions" == '23|0|3|0|4|0' ]]||{ echo "Unexpected persisted-volume Flyway state: $versions" >&2;exit 1; }
before="$(history_fingerprint)";[[ -n "$before" ]]
source_task="$(db_scalar "SELECT id FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1")";[[ -n "$source_task" ]]||{ echo 'A completed AUTO_COMPLETE task is required' >&2;exit 1; }
compose_all stop commerce-service commerce-portal-angular payment-simulator-web kafka-console
rabbit_task="$(db_scalar "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rabbit-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'RABBITMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rabbit-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$source_task; SELECT LAST_INSERT_ID();")"
rocket_task="$(db_scalar "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) SELECT 'm1-restart-rocket-$suffix',task_type,aggregate_type,aggregate_id,payload_json,'PROCESSING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 SECOND),'ROCKETMQ',FALSE,0,2,5,1,policy_version_snapshot,0,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),'crashed-rocket-worker',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scheduled_tasks WHERE id=$source_task; SELECT LAST_INSERT_ID();")"
compose_all restart commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
compose_all up -d --wait commerce-db payment-simulator-db logistics-simulator-db commerce-redis payment-simulator-redis kafka-broker rabbitmq-broker rocketmq-nameserver rocketmq-broker
compose_all restart payment-simulator-service logistics-simulator-service
compose_all up -d --wait payment-simulator-service logistics-simulator-service
export SCHEDULING_RABBITMQ_ENABLED=true SCHEDULING_ROCKETMQ_ENABLED=true SCHEDULING_LEASE_RECOVERY_INITIAL_DELAY=1s SCHEDULING_LEASE_RECOVERY_FIXED_DELAY=1s SCHEDULING_DATABASE_INITIAL_DELAY=1s SCHEDULING_DATABASE_FIXED_DELAY=2s
compose_all up -d --wait --force-recreate commerce-service commerce-portal-angular payment-simulator-web kafka-console
wait_task "$rabbit_task";wait_task "$rocket_task"
for task in "$rabbit_task" "$rocket_task";do
  audit="$(db_scalar "SELECT CONCAT(SUM(event_type='TASK_LEASE_EXPIRED'),'|',SUM(event_type='TASK_CLAIMED'),'|',SUM(event_type='TASK_SUCCEEDED')) FROM scheduled_task_events WHERE task_id=$task")"
  [[ "$audit" == '1|1|1' ]]||{ echo "Task $task recovery audit is $audit" >&2;exit 1; }
done
sources="$(db_scalar "SELECT GROUP_CONCAT(CONCAT(primary_trigger_snapshot,':',last_trigger_source) ORDER BY primary_trigger_snapshot SEPARATOR '|') FROM scheduled_tasks WHERE id IN ($rabbit_task,$rocket_task)")"
[[ "$sources" == 'RABBITMQ:RABBITMQ|ROCKETMQ:ROCKETMQ' ]]||{ echo "Recovered tasks used unexpected sources: $sources" >&2;exit 1; }
after="$(history_fingerprint)";[[ "$after" == "$before" ]]||{ echo "Historical data changed: before=$before after=$after" >&2;exit 1; }
[[ "$(curl -fsS http://localhost:18081/actuator/health|jq -r .status)" == UP ]]
[[ "$(curl -fsS http://localhost:18082/actuator/health|jq -r .status)" == UP ]]
[[ "$(curl -fsS http://localhost:18083/actuator/health|jq -r .status)" == UP ]]
passed=true
migration_evidence='V16->V23 rehearsal passed';[[ "$SKIP_MIGRATION" == true ]]&&migration_evidence='migration rehearsal skipped'
echo "Upgrade/restart recovery passed: $migration_evidence, persisted Flyway storefront/payment/logistics=V23/V3/V4, full service+MQ restart, RabbitMQ task=$rabbit_task and RocketMQ task=$rocket_task recovered exactly once, history=$after."

#!/usr/bin/env bash
set -euo pipefail
MAX_WAIT="${1:-90}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION_DIR="$PROJECT_ROOT/services/commerce-service/src/main/resources/db/migration"
suffix="$(date +%s)-$$-$RANDOM"
network="commerceflow-flyway-$suffix"
empty_db="commerceflow-flyway-empty-$suffix"
upgrade_db="commerceflow-flyway-upgrade-$suffix"
password='commerceflow-flyway-test'
flyway_image='redgate/flyway:12.4.0'

cleanup(){
  local status=$?;set +e
  docker rm -f "$empty_db" "$upgrade_db" >/dev/null 2>&1
  docker network rm "$network" >/dev/null 2>&1
  exit "$status"
}
trap cleanup EXIT

wait_mysql(){
  local container="$1" deadline=$((SECONDS+MAX_WAIT)) state
  while (( SECONDS < deadline ));do
    state="$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null||true)"
    [[ "$state" == healthy ]]&&return
    sleep 0.5
  done
  echo "$container did not become healthy" >&2;return 1
}
start_mysql(){
  local container="$1"
  docker run -d --name "$container" --network "$network" --tmpfs /var/lib/mysql \
    -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=commerceflow \
    --health-cmd "mysqladmin ping -h 127.0.0.1 -uroot -p$password --silent" \
    --health-interval 1s --health-timeout 3s --health-retries 60 mysql:8.4 >/dev/null
  wait_mysql "$container"
}
migrate(){
  local container="$1" target="${2:-}" args=() volume_args=() location
  [[ -n "$target" ]]&&args+=("-target=$target")
  if [[ -n "${FLYWAY_VOLUME_CONTAINER:-}" ]];then
    volume_args=(--volumes-from "$FLYWAY_VOLUME_CONTAINER");location="filesystem:$MIGRATION_DIR"
  else
    volume_args=(-v "$MIGRATION_DIR:/migration:ro");location='filesystem:/migration'
  fi
  docker run --rm --network "$network" "${volume_args[@]}" "$flyway_image" \
    "-url=jdbc:mysql://$container:3306/commerceflow?allowPublicKeyRetrieval=true&useSSL=false" \
    -user=root "-password=$password" -connectRetries=60 "-locations=$location" "${args[@]}" migrate
}
sql(){ docker exec "$1" mysql -N -uroot "-p$password" commerceflow -e "$2" 2>/dev/null; }

docker network create "$network" >/dev/null
start_mysql "$empty_db"
start_mysql "$upgrade_db"

migrate "$empty_db"
[[ "$(sql "$empty_db" 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;')" == 27 ]]
[[ "$(sql "$empty_db" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations');")" == 3 ]]
(( $(sql "$empty_db" "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='commerceflow' AND column_comment<>'';") >= 67 ))

migrate "$upgrade_db" 16
sql "$upgrade_db" "INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,created_at,updated_at) SELECT 'LEGACY-UPGRADE-$suffix',id,'PENDING_PAYMENT',42.00,'CNY','2026-01-01 00:00:00','2026-01-01 00:00:00' FROM app_users WHERE email='demo@example.com';"
migrate "$upgrade_db"
[[ "$(sql "$upgrade_db" 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;')" == 27 ]]
[[ "$(sql "$upgrade_db" "SELECT TIMESTAMPDIFF(SECOND,created_at,payment_due_at) FROM shop_orders WHERE order_number='LEGACY-UPGRADE-$suffix';")" == 1800 ]]
[[ "$(sql "$upgrade_db" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_name IN ('scheduled_tasks','shipment_records','inventory_reservations');")" == 3 ]]
(( $(sql "$upgrade_db" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='commerceflow' AND table_comment<>'';") >= 7 ))
if sql "$upgrade_db" "INSERT INTO scheduled_tasks(task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot) VALUES('invalid-status','PAYMENT_TIMEOUT','ORDER','1','{}','CORRUPTED',CURRENT_TIMESTAMP,'DATABASE',FALSE,0,30,3,1,1);";then
  echo 'Database accepted an illegal scheduled-task status' >&2;exit 1
fi

echo 'Flyway lifecycle verification passed: empty V1-V27, legacy V1-V16 -> V27 with deadline backfill, notification projection tables, core comments, and illegal status rejected.'

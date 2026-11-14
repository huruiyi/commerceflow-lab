#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker
BASE_URL="${1:-http://localhost:18081}"
MAX_WAIT_SECONDS="${2:-120}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temp_dir="$(new_temp_dir)"
admin_jar="$temp_dir/admin.cookies"
customer_jar="$temp_dir/customer.cookies"
rollback_jar="$temp_dir/rollback.cookies"

wait_healthy(){
  local container="$1" deadline=$((SECONDS+MAX_WAIT_SECONDS)) health=''
  while ((SECONDS < deadline));do
    health="$(docker inspect "$container" --format '{{.State.Health.Status}}' 2>/dev/null||true)"
    [[ "$health" == healthy ]]&&return
    sleep 2
  done
  echo "$container did not become healthy" >&2;return 1
}
set_mode(){
  local mode="$1" enabled="$2" delivery="$3"
  NOTIFICATION_STORAGE_ENABLED="$enabled" NOTIFICATION_STORAGE_CONSUMER_ENABLED="$enabled" \
    NOTIFICATION_STORAGE_MODE="$mode" NOTIFICATION_DELIVERY_MODE="$delivery" \
    docker compose up -d --no-deps --force-recreate commerce-service >/dev/null
  wait_healthy fairy-commerceflow-commerce-service-1
}
cleanup(){
  local status=$?;set +e
  docker compose start notification-db >/dev/null 2>&1
  set_mode COMMERCE false IN_PROCESS >/dev/null 2>&1
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
cd "$PROJECT_ROOT"
docker compose up -d notification-db kafka-broker >/dev/null
wait_healthy fairy-commerceflow-notification-db-1
set_mode SHADOW true KAFKA
IFS=$'\t' read -r admin_header admin_token <<<"$(storefront_login "$BASE_URL" admin@example.com "$admin_jar")"
while :;do
  backfill="$(json_request POST "$BASE_URL/api/admin/notification-storage/backfill" \
    "$admin_jar" "$admin_header" "$admin_token" '{}')"
  [[ "$(jq -r .completed <<<"$backfill")" == true ]]&&break
done
blocked="$(json_request POST "$BASE_URL/api/admin/notification-storage/check" \
  "$admin_jar" "$admin_header" "$admin_token" '{}')"
orphan_count="$(jq -r .orphanCount <<<"$blocked")"
if ((orphan_count > 0));then
  quarantine="$(json_request POST "$BASE_URL/api/admin/notification-storage/quarantine-orphans" \
    "$admin_jar" "$admin_header" "$admin_token" '{}')"
  [[ "$(jq -r .quarantinedCount <<<"$quarantine")" == "$orphan_count" ]]
fi
"$(dirname "${BASH_SOURCE[0]}")/verify-order-cancellation.sh" "$BASE_URL"
for _ in 1 2 3;do
  check="$(json_request POST "$BASE_URL/api/admin/notification-storage/check" \
    "$admin_jar" "$admin_header" "$admin_token" '{}')"
  [[ "$(jq -r .passed <<<"$check")" == true ]]
done
[[ "$(jq -r .readyToCutOver <<<"$check")" == true ]]
set_mode INDEPENDENT true KAFKA
IFS=$'\t' read -r customer_header customer_token \
  <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
notifications="$(json_request GET "$BASE_URL/api/notifications" "$customer_jar")"
[[ "$(jq length <<<"$notifications")" -gt 0 ]]
notification_id="$(docker compose exec -T notification-db mysql -N -unotification \
  -pnotification-password notification -e \
  'SELECT id FROM customer_notifications WHERE customer_id=1 ORDER BY created_at DESC,id DESC LIMIT 1;' 2>/dev/null)"
event_id="$(docker compose exec -T notification-db mysql -N -unotification \
  -pnotification-password notification -e \
  "SELECT event_id FROM customer_notifications WHERE id=$notification_id;" 2>/dev/null)"
json_request PATCH "$BASE_URL/api/notifications/$notification_id/read" "$customer_jar" \
  "$customer_header" "$customer_token" '{}' >/dev/null
[[ "$(docker compose exec -T commerce-db mysql -N -uroot -plocal-root-password commerceflow \
  -e "SELECT status FROM customer_notifications WHERE event_id='$event_id';" 2>/dev/null)" == READ ]]
docker compose stop notification-db >/dev/null
if curl --max-time 10 -fsS -b "$customer_jar" "$BASE_URL/api/notifications" >/dev/null 2>&1;then
  echo 'Independent query unexpectedly survived its database outage' >&2;exit 1
fi
set_mode COMMERCE false IN_PROCESS
IFS=$'\t' read -r rollback_header rollback_token \
  <<<"$(storefront_login "$BASE_URL" demo@example.com "$rollback_jar")"
rollback="$(json_request GET "$BASE_URL/api/notifications" "$rollback_jar")"
[[ "$(jq length <<<"$rollback")" -gt 0 ]]
echo 'Notification runtime cutover passed: backfill, quarantine, live Kafka mirror, three checks, independent read, database outage and Commerce Schema rollback.'

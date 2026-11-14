#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker
BASE_URL="${1:-http://localhost:18081}"
NOTIFICATION_URL="${2:-http://localhost:18086}"
TEMPO_URL="${3:-http://localhost:13200}"
MAX_WAIT_SECONDS="${4:-120}"
SKIP_BUILD="${5:-false}"
EXTERNAL_RUNTIME="${EXTERNAL_RUNTIME:-false}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
notification_was_running="$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q notification-service 2>/dev/null || true)"
tempo_was_running="$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q tempo 2>/dev/null || true)"

wait_url(){
  local url="$1" deadline=$((SECONDS+MAX_WAIT_SECONDS))
  while ((SECONDS < deadline));do
    curl -fsS --max-time 3 "$url" >/dev/null 2>&1 && return
    sleep 2
  done
  echo "Timed out waiting for $url" >&2;return 1
}
commerce_sql(){
  docker compose exec -T commerce-db mysql -N -uroot -plocal-root-password commerceflow \
    -e "$1" 2>/dev/null
}
notification_sql(){
  docker compose exec -T notification-db mysql -N -unotification -pnotification-password \
    notification -e "$1" 2>/dev/null
}
new_event(){
  local baseline event_id deadline
  baseline="$(commerce_sql 'SELECT COALESCE(MAX(id),0) FROM customer_notifications;')"
  "$(dirname "${BASH_SOURCE[0]}")/verify-order-cancellation.sh" "$BASE_URL" >&2
  deadline=$((SECONDS+MAX_WAIT_SECONDS))
  while ((SECONDS < deadline));do
    event_id="$(commerce_sql "SELECT event_id FROM customer_notifications WHERE id>$baseline ORDER BY id DESC LIMIT 1;")"
    [[ -n "$event_id" ]] && { printf '%s' "$event_id";return; }
    sleep 1
  done
  echo 'Commerce notification was not created' >&2;return 1
}
wait_shadow_event(){
  local event_id="$1" deadline=$((SECONDS+MAX_WAIT_SECONDS)) count inbox
  while ((SECONDS < deadline));do
    count="$(notification_sql "SELECT COUNT(*) FROM customer_notifications WHERE event_id='$event_id';")"
    inbox="$(notification_sql "SELECT COUNT(*) FROM notification_inbox WHERE consumer_name='commerceflow-independent-notification-v1' AND event_id='$event_id';")"
    [[ "$count" == 1 && "$inbox" == 1 ]] && return
    sleep 2
  done
  echo "Shadow event $event_id did not converge" >&2;return 1
}
cleanup(){
  local status=$?;set +e
  if [[ "$EXTERNAL_RUNTIME" != true ]];then
    NOTIFICATION_DELIVERY_MODE="${original_delivery_mode:-IN_PROCESS}" \
      NOTIFICATION_FALLBACK_TO_IN_PROCESS="${original_fallback:-false}" \
      NOTIFICATION_STORAGE_ENABLED="${original_storage_enabled:-false}" \
      NOTIFICATION_STORAGE_CONSUMER_ENABLED="${original_consumer_enabled:-false}" \
      NOTIFICATION_STORAGE_MODE="${original_storage_mode:-COMMERCE}" \
      TRACING_EXPORT_ENABLED="${original_tracing:-false}" \
      docker compose up -d --no-deps --force-recreate commerce-service >/dev/null 2>&1
    [[ -n "$notification_was_running" ]] || docker compose --profile notification-service stop notification-service >/dev/null 2>&1
    [[ -n "$tempo_was_running" ]] || docker compose --profile observability stop tempo >/dev/null 2>&1
  fi
  exit "$status"
}
trap cleanup EXIT
cd "$PROJECT_ROOT"
original_delivery_mode="${NOTIFICATION_DELIVERY_MODE-}"
original_fallback="${NOTIFICATION_FALLBACK_TO_IN_PROCESS-}"
original_storage_enabled="${NOTIFICATION_STORAGE_ENABLED-}"
original_consumer_enabled="${NOTIFICATION_STORAGE_CONSUMER_ENABLED-}"
original_storage_mode="${NOTIFICATION_STORAGE_MODE-}"
original_tracing="${TRACING_EXPORT_ENABLED-}"
export NOTIFICATION_DELIVERY_MODE=KAFKA NOTIFICATION_FALLBACK_TO_IN_PROCESS=true
export NOTIFICATION_STORAGE_ENABLED=false NOTIFICATION_STORAGE_CONSUMER_ENABLED=false
export NOTIFICATION_STORAGE_MODE=COMMERCE TRACING_EXPORT_ENABLED=true
if [[ "$SKIP_BUILD" != true && "$EXTERNAL_RUNTIME" != true ]];then
  docker compose --profile notification-service build commerce-service notification-service
fi
if [[ "$EXTERNAL_RUNTIME" != true ]];then
  docker compose --profile notification-service --profile observability up -d \
    notification-service tempo commerce-service >/dev/null
  docker compose up -d --no-deps --force-recreate commerce-service >/dev/null
  docker compose --profile notification-service up -d --no-deps --force-recreate notification-service >/dev/null
fi
wait_url "$BASE_URL/actuator/health"
wait_url "$NOTIFICATION_URL/actuator/health"
wait_url "$TEMPO_URL/ready"
live_event="$(new_event)"
wait_shadow_event "$live_event"
status="$(curl -fsS "$NOTIFICATION_URL/internal/shadow/status")"
[[ "$(jq -r .mode <<<"$status")" == SHADOW ]]
[[ "$(jq -r .consumerName <<<"$status")" == commerceflow-independent-notification-v1 ]]
traceparent="$(notification_sql "SELECT traceparent FROM notification_inbox WHERE event_id='$live_event' AND consumer_name='commerceflow-independent-notification-v1';")"
trace_id="$(cut -d- -f2 <<<"$traceparent")"
deadline=$((SECONDS+MAX_WAIT_SECONDS));services=''
while ((SECONDS < deadline));do
  trace="$(curl -fsS --max-time 5 "$TEMPO_URL/api/traces/$trace_id" 2>/dev/null || true)"
  services="$(jq -r '.batches[].resource.attributes[] | select(.key=="service.name") | .value.stringValue' <<<"${trace:-{}}" 2>/dev/null | sort -u || true)"
  grep -qx commerce-service <<<"$services" && grep -qx notification-service <<<"$services" && break
  sleep 2
done
grep -qx commerce-service <<<"$services"
grep -qx notification-service <<<"$services"
docker compose --profile notification-service stop notification-service >/dev/null
recovery_event="$(new_event)"
docker compose --profile notification-service start notification-service >/dev/null
wait_url "$NOTIFICATION_URL/actuator/health"
wait_shadow_event "$recovery_event"
export NOTIFICATION_DELIVERY_MODE=IN_PROCESS NOTIFICATION_FALLBACK_TO_IN_PROCESS=false
docker compose up -d --no-deps --force-recreate commerce-service >/dev/null
wait_url "$BASE_URL/actuator/health"
fallback_event="$(new_event)"
echo "Notification service shadow verification passed: live=$live_event, recovered=$recovery_event, fallback=$fallback_event, trace=$trace_id."

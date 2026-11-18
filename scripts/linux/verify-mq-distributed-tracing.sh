#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "$0")" && pwd)/_verification-db.sh"
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker

BASE_URL="${1:-${BASE_URL:-http://localhost:18081}}"
TEMPO_URL="${2:-${TEMPO_URL:-http://localhost:13200}}"
MAX_WAIT_SECONDS="${3:-${MAX_WAIT_SECONDS:-90}}"
SKIP_BUILD="${4:-${SKIP_BUILD:-false}}"
EXTERNAL_RUNTIME="${EXTERNAL_RUNTIME:-false}"
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
previous_trace="${TRACING_EXPORT_ENABLED-__unset__}"
previous_rabbit="${SCHEDULING_RABBITMQ_ENABLED-__unset__}"
previous_rocket="${SCHEDULING_ROCKETMQ_ENABLED-__unset__}"
previous_timeout="${ORDER_PAYMENT_TIMEOUT-__unset__}"
rabbit_was_running=false; rocket_was_running=false; tempo_was_running=false
[[ -n "$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q rabbitmq-broker 2>/dev/null)" ]] && rabbit_was_running=true
[[ -n "$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q rocketmq-broker 2>/dev/null)" ]] && rocket_was_running=true
[[ -n "$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q tempo 2>/dev/null)" ]] && tempo_was_running=true

wait_healthy() {
  local url="$1" deadline=$((SECONDS + MAX_WAIT_SECONDS))
  until curl -fsS --max-time 3 "$url" >/dev/null; do
    (( SECONDS >= deadline )) && { echo "Timed out waiting for $url" >&2; return 1; }
    sleep 2
  done
}

latest_task_id() { verify_db commerce "SELECT COALESCE(MAX(id),0) FROM scheduled_tasks WHERE primary_trigger_snapshot='$1';"; }

find_mq_trace() {
  local task_id="$1" system="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) search trace_id names
  local tag="commerceflow.task.id%3D$task_id"
  while (( SECONDS < deadline )); do
    sleep 2
    search="$(curl -fsS --max-time 5 "$TEMPO_URL/api/search?tags=$tag" 2>/dev/null || true)"
    [[ -n "$search" ]] || continue
    while read -r trace_id; do
      [[ -n "$trace_id" ]] || continue
      names="$(curl -fsS --max-time 5 "$TEMPO_URL/api/traces/$trace_id" | jq -r '.batches[].scopeSpans[].spans[].name')"
      if grep -Fxq "$system scheduled task publish" <<<"$names" &&
         grep -Fxq "$system scheduled task process" <<<"$names"; then
        echo "$trace_id"; return 0
      fi
    done < <(jq -r '.traces[]?.traceID' <<<"$search")
  done
  echo "$system task $task_id did not produce linked publish/process spans" >&2
  return 1
}

restore_value() {
  local name="$1" value="$2"
  if [[ "$value" == '__unset__' ]]; then unset "$name"; else export "$name=$value"; fi
}

restore_runtime() {
  [[ "$EXTERNAL_RUNTIME" == true ]] && return
  restore_value TRACING_EXPORT_ENABLED "$previous_trace"
  restore_value SCHEDULING_RABBITMQ_ENABLED "$previous_rabbit"
  restore_value SCHEDULING_ROCKETMQ_ENABLED "$previous_rocket"
  restore_value ORDER_PAYMENT_TIMEOUT "$previous_timeout"
  docker compose -f "$PROJECT_ROOT/compose.yaml" up -d --no-deps --force-recreate commerce-service >/dev/null
  [[ "$rabbit_was_running" == true ]] || docker compose -f "$PROJECT_ROOT/compose.yaml" stop rabbitmq-broker >/dev/null
  [[ "$rocket_was_running" == true ]] || docker compose -f "$PROJECT_ROOT/compose.yaml" stop rocketmq-broker rocketmq-nameserver >/dev/null
  [[ "$tempo_was_running" == true ]] || docker compose -f "$PROJECT_ROOT/compose.yaml" stop tempo >/dev/null
}
trap restore_runtime EXIT

if [[ "$EXTERNAL_RUNTIME" != true ]]; then
  [[ "$SKIP_BUILD" == true ]] || docker compose -f "$PROJECT_ROOT/compose.yaml" build commerce-service
  export TRACING_EXPORT_ENABLED=true SCHEDULING_RABBITMQ_ENABLED=true SCHEDULING_ROCKETMQ_ENABLED=true
  export ORDER_PAYMENT_TIMEOUT=8s SCHEDULING_RABBITMQ_PUBLISH_INITIAL_DELAY=1s
  export SCHEDULING_ROCKETMQ_PUBLISH_INITIAL_DELAY=1s
  docker compose -f "$PROJECT_ROOT/compose.yaml" --profile rabbitmq --profile rocketmq \
    --profile observability up -d rabbitmq-broker rocketmq-topic-init tempo >/dev/null
  docker compose -f "$PROJECT_ROOT/compose.yaml" up -d --no-deps --force-recreate commerce-service >/dev/null
fi
wait_healthy "$TEMPO_URL/ready"
wait_healthy "$BASE_URL/actuator/health"

rabbit_before="$(latest_task_id RABBITMQ)"
"$PROJECT_ROOT/scripts/linux/verify-rabbitmq-scheduling.sh" "$BASE_URL" "$MAX_WAIT_SECONDS"
rabbit_task="$(latest_task_id RABBITMQ)"
(( rabbit_task > rabbit_before )) || { echo 'RabbitMQ trace drill did not create a task' >&2; exit 1; }
rabbit_trace="$(find_mq_trace "$rabbit_task" rabbitmq)"

rocket_before="$(latest_task_id ROCKETMQ)"
"$PROJECT_ROOT/scripts/linux/verify-rocketmq-scheduling.sh" "$BASE_URL" "$MAX_WAIT_SECONDS"
rocket_task="$(latest_task_id ROCKETMQ)"
(( rocket_task > rocket_before )) || { echo 'RocketMQ trace drill did not create a task' >&2; exit 1; }
rocket_trace="$(find_mq_trace "$rocket_task" rocketmq)"

if [[ "$EXTERNAL_RUNTIME" == true ]]; then docker stop fairy-commerceflow-tempo >/dev/null
else docker compose -f "$PROJECT_ROOT/compose.yaml" stop tempo >/dev/null; fi
wait_healthy "$BASE_URL/actuator/health"
if [[ "$EXTERNAL_RUNTIME" == true ]]; then docker start fairy-commerceflow-tempo >/dev/null
else docker compose -f "$PROJECT_ROOT/compose.yaml" --profile observability up -d tempo >/dev/null; fi
wait_healthy "$TEMPO_URL/ready"
echo "MQ distributed tracing passed: RabbitMQ task=$rabbit_task trace=$rabbit_trace; RocketMQ task=$rocket_task trace=$rocket_trace; Tempo outage did not stop commerce-service."

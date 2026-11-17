#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl docker

BASE_URL="${1:-${BASE_URL:-http://localhost:18081}}"
TEMPO_URL="${2:-${TEMPO_URL:-http://localhost:13200}}"
MAX_WAIT_SECONDS="${3:-${MAX_WAIT_SECONDS:-60}}"
SKIP_BUILD="${4:-${SKIP_BUILD:-false}}"
EXTERNAL_RUNTIME="${EXTERNAL_RUNTIME:-false}"
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_ORIGIN="${BASE_URL%:*}"
export PAYMENT_UI_BASE_URL="${PAYMENT_UI_BASE_URL:-$SERVICE_ORIGIN:14201}"
previous_export="${TRACING_EXPORT_ENABLED-__unset__}"
tempo_was_running=false
[[ -n "$(docker compose -f "$PROJECT_ROOT/compose.yaml" ps -q tempo 2>/dev/null)" ]] && tempo_was_running=true

wait_healthy() {
  local url="$1" deadline=$((SECONDS + MAX_WAIT_SECONDS))
  until curl -fsS --max-time 3 "$url" >/dev/null; do
    (( SECONDS >= deadline )) && { echo "Timed out waiting for $url" >&2; return 1; }
    sleep 2
  done
}

restore_runtime() {
  [[ "$EXTERNAL_RUNTIME" == true ]] && return
  if [[ "$previous_export" == '__unset__' ]]; then unset TRACING_EXPORT_ENABLED; else export TRACING_EXPORT_ENABLED="$previous_export"; fi
  docker compose -f "$PROJECT_ROOT/compose.yaml" up -d --no-deps --force-recreate \
    commerce-service payment-simulator-service logistics-simulator-service >/dev/null
  [[ "$tempo_was_running" == true ]] || docker compose -f "$PROJECT_ROOT/compose.yaml" stop tempo >/dev/null
}
trap restore_runtime EXIT

if [[ "$EXTERNAL_RUNTIME" != true && "$SKIP_BUILD" != true ]]; then
  docker compose -f "$PROJECT_ROOT/compose.yaml" build \
    commerce-service payment-simulator-service logistics-simulator-service
fi
if [[ "$EXTERNAL_RUNTIME" != true ]]; then
  export TRACING_EXPORT_ENABLED=true
  docker compose -f "$PROJECT_ROOT/compose.yaml" --profile observability up -d tempo >/dev/null
  docker compose -f "$PROJECT_ROOT/compose.yaml" up -d --no-deps --force-recreate \
    commerce-service payment-simulator-service logistics-simulator-service >/dev/null
fi
wait_healthy "$TEMPO_URL/ready"
wait_healthy "$BASE_URL/actuator/health"
wait_healthy "$SERVICE_ORIGIN:18082/actuator/health"
wait_healthy "$SERVICE_ORIGIN:18083/actuator/health"

"$PROJECT_ROOT/scripts/linux/verify-payment-notifications.sh" "$BASE_URL"
temp_dir="$(new_temp_dir)"
merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
deadline=$((SECONDS + MAX_WAIT_SECONDS)); paid=null
while (( SECONDS < deadline )); do
  paid="$(json_request GET "$BASE_URL/api/merchant/orders" "$merchant_jar" | jq -c '[.[] | select(.status == "PAID")][0]')"
  [[ "$paid" != null ]] && break
  sleep 1
done
[[ "$paid" != null ]] || { echo 'No paid order is available for the trace drill' >&2; exit 1; }
order_id="$(jq -r .id <<<"$paid")"
confirmed="$(json_request POST "$BASE_URL/api/merchant/orders/$order_id/confirm" "$merchant_jar" "$csrf_header" "$csrf_token" '{}')"
jq -e '.status == "CONFIRMED"' <<<"$confirmed" >/dev/null

trace_id="$(openssl rand -hex 16)"
parent_id="$(openssl rand -hex 8)"
body="$(jq -nc --arg carrier 'Trace Express' --arg tracking "TRACE-$order_id" '{carrier:$carrier,trackingNumber:$tracking}')"
shipped="$(curl -fsS -b "$merchant_jar" -X POST "$BASE_URL/api/merchant/orders/$order_id/ship" \
  -H "$csrf_header: $csrf_token" -H "traceparent: 00-$trace_id-$parent_id-01" \
  -H 'Content-Type: application/json' --data "$body")"
jq -e '.status == "SHIPPED"' <<<"$shipped" >/dev/null

deadline=$((SECONDS + MAX_WAIT_SECONDS)); services='[]'
while (( SECONDS < deadline )); do
  sleep 2
  trace="$(curl -fsS --max-time 5 "$TEMPO_URL/api/traces/$trace_id" 2>/dev/null || true)"
  if [[ -n "$trace" ]]; then
    services="$(jq -c '[.batches[].resource.attributes[] | select(.key == "service.name") | .value.stringValue] | unique' <<<"$trace")"
    [[ "$(jq length <<<"$services")" -ge 2 ]] && break
  fi
done
jq -e 'index("commerce-service") and index("logistics-simulator-service")' <<<"$services" >/dev/null || {
  echo "Cross-process trace did not converge (services=$services)" >&2; exit 1;
}

if [[ "$EXTERNAL_RUNTIME" == true ]]; then
  docker stop fairy-commerceflow-tempo >/dev/null
else
  docker compose -f "$PROJECT_ROOT/compose.yaml" stop tempo >/dev/null
fi
wait_healthy "$BASE_URL/actuator/health"
wait_healthy "$SERVICE_ORIGIN:18083/actuator/health"
if [[ "$EXTERNAL_RUNTIME" == true ]]; then
  docker start fairy-commerceflow-tempo >/dev/null
else
  docker compose -f "$PROJECT_ROOT/compose.yaml" --profile observability up -d tempo >/dev/null
fi
wait_healthy "$TEMPO_URL/ready"

echo "Distributed tracing verification passed: trace=$trace_id, services=$services, Tempo outage degraded without stopping business services."

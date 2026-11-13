#!/usr/bin/env bash
set -euo pipefail

PROMETHEUS_URL="${1:-http://localhost:19090}"
ALERTMANAGER_URL="${2:-http://localhost:19093}"
WEBHOOK_URL="${3:-http://localhost:18085}"
MAX_WAIT_SECONDS="${4:-90}"
SKIP_START="${5:-false}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "$SKIP_START" != true ]]; then
  docker compose --project-directory "$PROJECT_ROOT" --profile observability up -d \
    alert-webhook alertmanager prometheus grafana
fi

wait_json() {
  local url="$1" filter="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) response
  until ((SECONDS >= deadline)); do
    if response="$(curl -fsS --max-time 5 "$url" 2>/dev/null)" && jq -e "$filter" <<<"$response" >/dev/null; then
      printf '%s' "$response"
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

wait_json "$PROMETHEUS_URL/api/v1/status/buildinfo" '.status == "success"' >/dev/null
wait_json "$ALERTMANAGER_URL/api/v2/status" '.versionInfo.version != null' >/dev/null
wait_json "$WEBHOOK_URL/healthz" '.status == "UP"' >/dev/null

docker compose --project-directory "$PROJECT_ROOT" exec -T prometheus \
  promtool check config /etc/prometheus/prometheus.yml
docker compose --project-directory "$PROJECT_ROOT" exec -T prometheus \
  promtool test rules /etc/prometheus/tests/commerceflow-alerts.test.yml
docker compose --project-directory "$PROJECT_ROOT" exec -T alertmanager \
  amtool check-config /etc/alertmanager/alertmanager.yml

targets="$(wait_json "$PROMETHEUS_URL/api/v1/targets" \
  '[.data.activeTargets[] | select(.health == "up")] | length == 3')"
jobs="$(jq -r '[.data.activeTargets[].labels.job] | unique | join(",")' <<<"$targets")"
if [[ "$jobs" != 'commerce-service,logistics-simulator-service,payment-simulator-service' ]]; then
  echo "Unexpected Prometheus jobs: $jobs" >&2
  exit 1
fi

rules="$(wait_json "$PROMETHEUS_URL/api/v1/rules" \
  '[.data.groups[].rules[] | select(.name == "CommerceFlowWatchdog")] | length == 1')"
rule_count="$(jq '[.data.groups[].rules[] | select(.type == "alerting")] | length' <<<"$rules")"
if ((rule_count < 10)); then
  echo "Expected at least 10 alert rules, found $rule_count." >&2
  exit 1
fi

wait_json "$ALERTMANAGER_URL/api/v2/alerts" \
  '[.[].labels.alertname | select(. == "CommerceFlowWatchdog")] | length > 0' >/dev/null

delivery="$(wait_json "$WEBHOOK_URL/api/alerts" \
  '[.events[].payload.alerts[]?.labels.alertname | select(. == "CommerceFlowWatchdog")] | length > 0')"
watchdog_deliveries="$(jq '[.events[].payload.alerts[]?.labels.alertname | select(. == "CommerceFlowWatchdog")] | length' <<<"$delivery")"
echo "Alerting verification passed: jobs=$jobs, rules=$rule_count, watchdog deliveries=$watchdog_deliveries."

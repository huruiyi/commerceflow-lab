#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
COMMERCEFLOW_USE_DEPLOYMENT_COMPOSE=true
http=curl
if [[ -n "${WSL_DISTRO_NAME:-}" ]] && command -v curl.exe >/dev/null; then http=curl.exe; fi

PROMETHEUS_URL="${1:-http://localhost:19090}"
ALERTMANAGER_URL="${2:-http://localhost:19093}"
WEBHOOK_URL="${3:-http://localhost:18085}"
MAX_WAIT_SECONDS="${4:-90}"
SKIP_START="${5:-false}"

if [[ "$SKIP_START" != true ]]; then
  compose --profile observability up -d \
    alert-webhook alertmanager prometheus grafana
  compose restart prometheus >/dev/null
fi

wait_json() {
  local url="$1" filter="$2" deadline=$((SECONDS + MAX_WAIT_SECONDS)) response
  until ((SECONDS >= deadline)); do
    if response="$("$http" -fsS --max-time 5 "$url" 2>/dev/null)" && jq -e "$filter" <<<"$response" >/dev/null; then
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

compose exec -T prometheus \
  promtool check config /etc/prometheus/prometheus.yml
compose exec -T prometheus \
  promtool test rules /etc/prometheus/tests/commerceflow-alerts.test.yml /etc/prometheus/tests/fulfillment-alerts.test.yml
compose exec -T alertmanager \
  amtool check-config /etc/alertmanager/alertmanager.yml

route="$(wait_json "$PROMETHEUS_URL/api/v1/query?query=commerceflow_fulfillment_remote_enabled" '.data.result | length == 1')"
required_jobs='["commerce-service","logistics-simulator-service","payment-orchestration-service","payment-simulator-service"]'
remote_fulfillment="$(jq -r '.data.result[0].value[1]' <<<"$route")"
if [[ "$remote_fulfillment" == 1 ]]; then
  required_jobs="$(jq -c '. + ["fulfillment-service"]' <<<"$required_jobs")"
fi
targets="$(wait_json "$PROMETHEUS_URL/api/v1/targets" \
  "$required_jobs - [.data.activeTargets[] | select(.health == \"up\").labels.job] | length == 0")"
jobs="$(jq -r '[.data.activeTargets[].labels.job] | unique | join(",")' <<<"$targets")"
if [[ "$jobs" != 'commerce-service,fulfillment-service,logistics-simulator-service,payment-orchestration-service,payment-simulator-service' ]]; then
  echo "Unexpected Prometheus jobs: $jobs" >&2
  exit 1
fi
if [[ "$remote_fulfillment" == 1 ]]; then
  for metric in commerceflow_fulfillment_execution_enabled commerceflow_fulfillment_metrics_refresh_success; do
    wait_json "$PROMETHEUS_URL/api/v1/query?query=$metric" \
      '[.data.result[] | select(.metric.job == "fulfillment-service" and .value[1] == "1")] | length == 1' >/dev/null
  done
fi

payment_metrics="$("$http" -fsS http://localhost:18090/actuator/prometheus)"
expected_metrics=(
  'commerceflow_payment_outbox_pending 0.0'
  'commerceflow_payment_command_outbox_pending 0.0'
  'commerceflow_payment_process_pending 0.0'
  'commerceflow_payment_process_recovery_required 0.0'
  'commerceflow_payment_orphan_open 0.0'
  'commerceflow_payment_refund_processing 0.0'
  'commerceflow_payment_metrics_refresh_success 1.0'
  'commerceflow_payment_accepting_traffic 1.0'
  'commerceflow_payment_effects_enabled 1.0'
  'commerceflow_payment_shadow_enabled 0.0'
)
for expected_metric in "${expected_metrics[@]}"; do
  grep -Fqx "$expected_metric" <<<"$payment_metrics" || {
    echo "Payment runtime metric is missing or unexpected: $expected_metric" >&2
    exit 1
  }
done

rules="$(wait_json "$PROMETHEUS_URL/api/v1/rules" \
  '[.data.groups[].rules[] | select(.name == "CommerceFlowWatchdog")] | length == 1')"
rule_count="$(jq '[.data.groups[].rules[] | select(.type == "alerting")] | length' <<<"$rules")"
if ((rule_count < 21)); then
  echo "Expected at least 21 alert rules, found $rule_count." >&2
  exit 1
fi

wait_json "$ALERTMANAGER_URL/api/v2/alerts" \
  '[.[].labels.alertname | select(. == "CommerceFlowWatchdog")] | length > 0' >/dev/null

delivery="$(wait_json "$WEBHOOK_URL/api/alerts" \
  '[.events[].payload.alerts[]?.labels.alertname | select(. == "CommerceFlowWatchdog")] | length > 0')"
watchdog_deliveries="$(jq '[.events[].payload.alerts[]?.labels.alertname | select(. == "CommerceFlowWatchdog")] | length' <<<"$delivery")"
echo "Alerting verification passed: jobs=$jobs, rules=$rule_count, watchdog deliveries=$watchdog_deliveries."

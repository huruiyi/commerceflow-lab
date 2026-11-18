#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"

source_sql() {
  compose exec -T -e MYSQL_PWD=local-root-password commerce-db \
    mysql -uroot -Nse "$1" commerceflow
}

target_sql() {
  compose exec -T -e MYSQL_PWD=local-payments-root-password payment-db \
    mysql -uroot -Nse "$1" payments
}

wait_healthy() {
  local service="$1" deadline=$((SECONDS + 90)) health=''
  while ((SECONDS < deadline)); do
    health="$(compose ps --format json "$service" 2>/dev/null \
      | jq -r '.Health // empty' | tr -d '\r' | tail -1 || true)"
    [[ "$health" == healthy ]] && return 0
    sleep 2
  done
  echo "$service did not become healthy" >&2
  return 1
}

source_sequence_before="$(source_sql 'SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;')"
"$SCRIPT_DIR/audit-payment-cutover-readiness.sh"

restore_services() {
  compose start payment-db payment-simulator-service payment-orchestration-service >/dev/null || true
}
trap restore_services EXIT

"$SCRIPT_DIR/verify-payment-runtime-cutover.sh" "$BASE_URL"
"$SCRIPT_DIR/verify-order-cancellation.sh" "$BASE_URL"

compose stop payment-orchestration-service >/dev/null
if curl -fsS --max-time 3 http://localhost:18090/actuator/health >/dev/null 2>&1; then
  echo 'Stopped payment service still answered health checks' >&2
  exit 1
fi
compose start payment-orchestration-service >/dev/null
wait_healthy payment-orchestration-service

compose restart payment-simulator-service >/dev/null
wait_healthy payment-simulator-service

compose stop payment-db >/dev/null
sleep 3
if [[ "$(curl -sS --max-time 3 http://localhost:18090/actuator/health | jq -r '.status // "DOWN"' 2>/dev/null || echo DOWN)" == UP ]]; then
  echo 'Payment database outage was hidden' >&2
  exit 1
fi
compose start payment-db >/dev/null
wait_healthy payment-db
compose restart payment-orchestration-service >/dev/null
wait_healthy payment-orchestration-service

"$SCRIPT_DIR/verify-payment-runtime-cutover.sh" "$BASE_URL"
restore_services
wait_healthy payment-db
wait_healthy payment-simulator-service
wait_healthy payment-orchestration-service
trap - EXIT

source_sequence_after="$(source_sql 'SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;')"
[[ "$source_sequence_after" == "$source_sequence_before" ]] || {
  echo "Commerce payment source changed after CUTOVER: $source_sequence_before -> $source_sequence_after" >&2
  exit 1
}
source_grants="$(source_sql "SELECT COUNT(*) FROM information_schema.table_privileges WHERE grantee='''commerce_runtime''@''%''' AND table_schema='commerceflow' AND table_name IN ('payment_records','payment_merchant_accounts','payment_notifications','payment_events','payment_idempotency_locks','payment_reconciliation_issue','payment_outbox','kafka_consumed_event','payment_process_managers','payment_command_outbox','payment_result_inbox','refund_records','refund_events','refund_notifications');")"
[[ "$source_grants" == 0 ]] || { echo "Commerce runtime still has $source_grants payment source grants" >&2; exit 1; }

pending="$(target_sql "SELECT COUNT(*) FROM payment_outbox WHERE status IN ('NEW','RETRY','PROCESSING'); SELECT COUNT(*) FROM payment_command_outbox WHERE status IN ('NEW','RETRY','IN_FLIGHT'); SELECT COUNT(*) FROM payment_process_managers WHERE status<>'COMPLETED'; SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE status='OPEN';")"
[[ "$(tr -d '0\r\n' <<<"$pending")" == '' ]] || { echo "Payment runtime did not drain: $pending" >&2; exit 1; }

metrics="$(curl -fsS http://localhost:18090/actuator/prometheus)"
for expected in \
  'commerceflow_payment_metrics_refresh_success 1.0' \
  'commerceflow_payment_accepting_traffic 1.0' \
  'commerceflow_payment_effects_enabled 1.0' \
  'commerceflow_payment_shadow_enabled 0.0'; do
  grep -Fqx "$expected" <<<"$metrics" || { echo "Unexpected payment metric: $expected" >&2; exit 1; }
done

echo "Payment post-CUTOVER observation passed: source sequence=$source_sequence_after unchanged, grants=0, pending=0, service/database/simulator recovery=true."

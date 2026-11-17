#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-${1:-http://localhost:18090}}"
EVENT_ID="$(cat /proc/sys/kernel/random/uuid)"
ORDER_ID="$((990000000000 + RANDOM + 1000))"
TRACE='00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'

fail(){ echo "Payment effects rehearsal failed: $*" >&2;exit 1; }
sql(){ docker compose exec -T -e MYSQL_PWD=payments-password payment-db \
  mysql -upayments -N -B payments -e "$1" 2>/dev/null; }
cleanup(){
  sql "DELETE FROM payment_outbox WHERE event_id='$EVENT_ID';" >/dev/null 2>&1 || true
  export PAYMENT_ACCEPTING_TRAFFIC=false PAYMENT_EFFECTS_ENABLED=false PAYMENT_SHADOW_ENABLED=true
  docker compose up -d --force-recreate --wait payment-orchestration-service >/dev/null || true
  unset PAYMENT_ACCEPTING_TRAFFIC PAYMENT_EFFECTS_ENABLED PAYMENT_SHADOW_ENABLED
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
docker compose up -d --wait payment-db order-service inventory-service >/dev/null
payload="{\"result\":{\"schemaVersion\":1,\"resultId\":\"$EVENT_ID\",\"resultType\":\"PAYMENT_SUCCEEDED\",\"outcome\":\"SUCCEEDED\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"producer\":\"effects-rehearsal\",\"orderId\":$ORDER_ID,\"paymentNumber\":\"PAY-EFFECT-$ORDER_ID\",\"refundNumber\":null,\"sourceStatus\":\"SUCCESS\",\"errorCode\":null,\"message\":\"Synthetic missing-order rehearsal\",\"correlationId\":\"payment-$ORDER_ID\",\"causationId\":\"$EVENT_ID\",\"traceparent\":\"$TRACE\"},\"applicationStatus\":\"APPLIED_PAYMENT_SUCCEEDED\"}"
sql "INSERT INTO payment_outbox(event_id,aggregate_type,aggregate_id,event_type,topic,payload,status,attempts,next_attempt_at,last_error,created_at,published_at) VALUES ('$EVENT_ID','PAYMENT','PAY-EFFECT-$ORDER_ID','PAYMENT_SUCCEEDED','payment-result-events','$payload','NEW',0,CURRENT_TIMESTAMP(6),NULL,CURRENT_TIMESTAMP(6),NULL);" >/dev/null

export PAYMENT_ACCEPTING_TRAFFIC=true PAYMENT_EFFECTS_ENABLED=true PAYMENT_SHADOW_ENABLED=false
docker compose up -d --force-recreate --wait payment-orchestration-service >/dev/null
mode="$(curl -fsS "$BASE_URL/internal/v1/payment-shadow/mode")"
[[ "$mode" == *'"acceptingTraffic":true'* && "$mode" == *'"effectsEnabled":true'* &&
   "$mode" == *'"shadowEnabled":false'* ]] || fail 'temporary effects mode was not enabled'
deadline=$((SECONDS + 45));status=''
while (( SECONDS < deadline ));do status="$(sql "SELECT status FROM payment_outbox WHERE event_id='$EVENT_ID';")"
  [[ "$status" == RETRY ]]&&break;sleep 1;done
[[ "$status" == RETRY ]]||fail "Outbox did not reach RETRY; current=$status"
error="$(sql "SELECT last_error FROM payment_outbox WHERE event_id='$EVENT_ID';")"
[[ "$error" == *'Order was not found'* ]]||fail "unexpected retry evidence: $error"
order_count="$(docker compose exec -T -e MYSQL_PWD=orders-password order-db mysql -uorders -N -B \
  orders -e "SELECT COUNT(*) FROM shop_orders WHERE id=$ORDER_ID;" 2>/dev/null)"
inventory_count="$(docker compose exec -T -e MYSQL_PWD=inventory-password inventory-db \
  mysql -uinventory -N -B inventory -e \
  "SELECT COUNT(*) FROM inventory_command_inbox WHERE order_id=$ORDER_ID;" 2>/dev/null)"
[[ "$order_count" == 0 && "$inventory_count" == 0 ]]||fail 'unexpected downstream business write'
echo "Payment effects retry rehearsal passed: event $EVENT_ID, missing order $ORDER_ID, no inventory side effect."

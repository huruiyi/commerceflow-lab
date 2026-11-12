#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"

topics="$(compose exec -T kafka-broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-broker:9092 --list)"
for required in payment-events order-events payment-callback-events payment-reconciliation-events merchant-notification-events payment-events-dlq; do
  grep -Fxq "$required" <<<"$topics" || { echo "Kafka topic missing: $required" >&2; exit 1; }
done
deadline=$((SECONDS + 40)); row=''; consumed=0
while ((SECONDS < deadline)); do
  row="$(compose exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password commerceflow -e "SELECT CONCAT(status,':',attempts) FROM payment_outbox ORDER BY id DESC LIMIT 1" 2>/dev/null | tr -d '\r')"
  consumed="$(compose exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password commerceflow -e 'SELECT COUNT(*) FROM kafka_consumed_event' 2>/dev/null | tr -d '\r')"
  [[ "$row" == PUBLISHED:* && "$consumed" =~ ^[0-9]+$ && "$consumed" -gt 0 ]] && break
  sleep 2
done
[[ "$row" == PUBLISHED:* && "$consumed" -gt 0 ]] || { echo "Outbox was not published and consumed: outbox=$row consumed=$consumed" >&2; exit 1; }
echo "05A-5 verification passed: outbox=$row consumed-events=$consumed"

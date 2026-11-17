#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker
BASE_URL="${1:-http://localhost:18081}"
MAX_WAIT_SECONDS="${2:-120}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temp_dir="$(new_temp_dir)"
customer_jar="$temp_dir/customer.cookies"

wait_healthy() {
  local container="$1" deadline=$((SECONDS + MAX_WAIT_SECONDS)) health=''
  while ((SECONDS < deadline)); do
    health="$(docker inspect "$container" --format '{{.State.Health.Status}}' 2>/dev/null || true)"
    [[ "$health" == healthy ]] && return
    sleep 2
  done
  echo "$container did not become healthy" >&2
  return 1
}

cleanup() {
  local status=$?
  set +e
  docker compose start notification-service >/dev/null 2>&1
  wait_healthy fairy-commerceflow-notification-service >/dev/null 2>&1
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
docker compose up -d --wait notification-service commerce-service >/dev/null
"$(dirname "${BASH_SOURCE[0]}")/verify-order-cancellation.sh" "$BASE_URL"
IFS=$'\t' read -r customer_header customer_token \
  <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
notifications="$(json_request GET "$BASE_URL/api/notifications" "$customer_jar")"
event_id="$(jq -er '.[0].eventId' <<<"$notifications")"
encoded_event_id="$(jq -nr --arg value "$event_id" '$value|@uri')"
json_request PATCH "$BASE_URL/api/notifications/$encoded_event_id/read" "$customer_jar" \
  "$customer_header" "$customer_token" '{}' >/dev/null
state="$(docker compose exec -T notification-db mysql -N -unotification \
  -pnotification-password notification \
  -e "SELECT status FROM customer_notifications WHERE event_id='$event_id';" 2>/dev/null)"
[[ "$state" == READ ]]

docker compose stop notification-service >/dev/null
status="$(curl --max-time 10 -sS -o "$temp_dir/outage.json" -w '%{http_code}' \
  -b "$customer_jar" "$BASE_URL/api/notifications")"
[[ "$status" == 503 ]]
[[ "$(curl -fsS "$BASE_URL/actuator/health" | jq -r .status)" == UP ]]

echo 'Notification single-owner runtime passed: Kafka delivery, signed proxy, eventId read, stable 503 and core-commerce isolation.'

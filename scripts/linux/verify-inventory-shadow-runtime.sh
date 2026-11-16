#!/usr/bin/env bash
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl docker openssl
BASE_URL="${1:-http://localhost:18088}"
SECRET='demo-inventory-internal-secret'

cd "$PROJECT_ROOT"
docker compose --profile inventory-shadow up -d --build --wait inventory-service >/dev/null
docker compose --profile inventory-shadow run --rm inventory-data-migrator
docker compose --profile inventory-shadow run --rm inventory-data-migrator

health="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")"
[[ "$health" == 200 ]]
payload='{"schemaVersion":1}'
unsigned="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  --data "$payload" "$BASE_URL/internal/v1/inventory/commands")"
[[ "$unsigned" == 401 ]]

timestamp="$(date +%s)"
request_id="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || printf '00000000-0000-4000-8000-%012d' "$timestamp")"
target='/internal/v1/inventory/commands'
content_hash="$(printf '%s' "$payload" | openssl dgst -sha256 | awk '{print $2}')"
canonical="POST
$target
$timestamp
$request_id
$content_hash"
signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')"
closed="$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -H 'X-CommerceFlow-Service: commerce-service' \
  -H "X-CommerceFlow-Timestamp: $timestamp" \
  -H "X-CommerceFlow-Request-Id: $request_id" \
  -H "X-CommerceFlow-Content-SHA256: $content_hash" \
  -H "X-CommerceFlow-Signature: $signature" --data "$payload" "$BASE_URL$target")"
[[ "$closed" == 503 ]]

state="$(docker compose exec -T inventory-db mysql -N -uinventory -pinventory-password inventory \
  -e "SELECT CONCAT(status,'|',generation) FROM inventory_migration_state WHERE migration_key='inventory-v34-shadow-snapshot';" 2>/dev/null)"
[[ "$state" =~ ^READY\|([2-9]|[1-9][0-9]+)$ ]]
echo "Inventory shadow runtime passed: health UP, migration $state, unsigned 401, signed gate 503."

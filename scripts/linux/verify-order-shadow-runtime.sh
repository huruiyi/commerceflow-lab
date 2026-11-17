#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"
fail(){ echo "Order shadow verification failed: $*" >&2; exit 1; }
BASE_URL="${1:-http://localhost:18089}"

docker compose up -d --build --wait order-service >/dev/null
before_generation="$(curl -fsS "$BASE_URL/internal/v1/order-shadow/watermark" |
  sed -nE 's/.*"generation":([0-9]+).*/\1/p')"
snapshot="$(curl -fsS -X POST "$BASE_URL/internal/v1/order-shadow/snapshot")"
snapshot_generation="$(sed -nE 's/.*"generation":([0-9]+).*/\1/p' <<<"$snapshot")"
((snapshot_generation > before_generation)) || fail 'repeatable snapshot did not advance generation'

source_sequence="$(docker compose exec -T commerce-db mysql -uroot -plocal-root-password \
  --batch --skip-column-names commerceflow -e \
  'UPDATE shop_orders SET status=status WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM shop_orders) s); SELECT last_sequence FROM order_shadow_change_clock WHERE singleton_id=1;' |
  tail -n 1)"
for _ in {1..15}; do
  watermark="$(curl -fsS "$BASE_URL/internal/v1/order-shadow/watermark")"
  target_sequence="$(sed -nE 's/.*"sourceSequence":([0-9]+).*/\1/p' <<<"$watermark")"
  ((target_sequence >= source_sequence)) && break
  sleep 2
done
((target_sequence >= source_sequence)) || fail 'incremental catch-up did not reach source watermark'

audit="$(curl -fsS -X POST "$BASE_URL/internal/v1/order-shadow/audit")"
[[ "$audit" != *'"status":"MISMATCH"'* ]] || fail 'source/target audit is not clean'
[[ "$(grep -o '"status":"MATCH"' <<<"$audit" | wc -l)" == 7 ]] || fail 'expected 7 table audits'
open_orphans="$(docker compose exec -T order-db mysql -uorders -porders-password \
  --batch --skip-column-names orders -e \
  "SELECT COUNT(*) FROM order_shadow_orphan_quarantine WHERE status='OPEN'")"
[[ "$open_orphans" == 0 ]] || fail "$open_orphans orphan rows remain open"

status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' "$BASE_URL/internal/v1/orders/commands" \
  --data '{"schemaVersion":1,"commandId":"7ef4f485-f326-4cd2-91bf-d589e78f4d80","commandType":"CANCEL","occurredAt":"2026-11-17T00:00:00Z","producer":"commerce-service","orderId":1,"actorId":1,"expectedStatus":"PENDING_PAYMENT","reason":"closed-gate-check","correlationId":"closed-gate","causationId":"closed-gate","traceparent":"00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"}')"
[[ "$status" == 503 ]] || fail "remote V1 gate returned HTTP $status"

echo "Order shadow verification passed: generation $snapshot_generation, watermark $target_sequence, 7 table audits MATCH, no open orphan, remote V1 gate closed."

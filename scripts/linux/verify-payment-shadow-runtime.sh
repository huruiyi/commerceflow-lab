#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"
if [[ "${COMPOSE_PROJECT_NAME:-}" != fairy-commerceflow-shadow-?* ]] ||
  ! docker compose config --services | grep -Fx 'legacy-shadow-access' >/dev/null; then
  echo 'Use compose.yaml and compose.legacy-shadow.yaml in an isolated fairy-commerceflow-shadow-* project.' >&2
  exit 1
fi
fail(){ echo "Payment shadow verification failed: $*" >&2; exit 1; }
BASE_URL="${1:-http://localhost:18090}"
source_sql(){ docker compose exec -T commerce-db mysql -uroot -plocal-root-password \
  --batch --skip-column-names commerceflow -e "$1" 2>/dev/null | tail -n 1; }
target_sql(){ docker compose exec -T payment-db mysql -upayments -ppayments-password \
  --batch --skip-column-names payments -e "$1" 2>/dev/null | tail -n 1; }

docker compose up -d --force-recreate --wait commerce-schema-migrator >/dev/null
docker compose up -d --build --wait payment-orchestration-service >/dev/null

mode="$(curl -fsS "$BASE_URL/internal/v1/payment-shadow/mode")"
[[ "$mode" == *'"acceptingTraffic":false'* && "$mode" == *'"shadowEnabled":true'* ]] ||
  fail 'service is not in closed-traffic shadow mode'

before="$(curl -fsS "$BASE_URL/internal/v1/payment-shadow/watermark")"
before_generation="$(sed -nE 's/.*"generation":([0-9]+).*/\1/p' <<<"$before")"
snapshot="$(curl -fsS -X POST "$BASE_URL/internal/v1/payment-shadow/snapshot")"
snapshot_generation="$(sed -nE 's/.*"generation":([0-9]+).*/\1/p' <<<"$snapshot")"
((snapshot_generation > before_generation)) || fail 'repeatable snapshot did not advance generation'

source_sequence="$(source_sql 'UPDATE payment_records SET version=version WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM payment_records) p); SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;')"
for _ in {1..30}; do
  curl -fsS -X POST "$BASE_URL/internal/v1/payment-shadow/catch-up" >/dev/null
  watermark="$(curl -fsS "$BASE_URL/internal/v1/payment-shadow/watermark")"
  target_sequence="$(sed -nE 's/.*"sourceSequence":([0-9]+).*/\1/p' <<<"$watermark")"
  ((target_sequence >= source_sequence)) && break
  sleep 1
done
((target_sequence >= source_sequence)) || fail 'incremental catch-up did not reach source watermark'

fixture="$(source_sql 'SELECT CONCAT(e.id,CHAR(58),e.payment_id) FROM payment_events e JOIN payment_records p ON p.id=e.payment_id ORDER BY e.id LIMIT 1;')"
event_id="${fixture%%:*}"; payment_id="${fixture##*:}"
target_sql "SET FOREIGN_KEY_CHECKS=0; DELETE FROM payment_events WHERE id=$event_id; DELETE FROM payment_records WHERE id=$payment_id; SET FOREIGN_KEY_CHECKS=1; SELECT 1;" >/dev/null
orphan_sequence="$(source_sql "UPDATE payment_events SET detail=detail WHERE id=$event_id; SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;")"
curl -fsS -X POST "$BASE_URL/internal/v1/payment-shadow/catch-up" >/dev/null
[[ "$(target_sql "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE change_sequence=$orphan_sequence AND status='OPEN';")" == 1 ]] ||
  fail 'foreign-key orphan was not quarantined'

source_sql "UPDATE payment_records SET version=version WHERE id=$payment_id; SELECT last_sequence FROM payment_shadow_change_clock WHERE singleton_id=1;" >/dev/null
curl -fsS -X POST "$BASE_URL/internal/v1/payment-shadow/catch-up" >/dev/null
resolved=0
for _ in {1..15}; do
  sleep 2
  resolved="$(target_sql "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE change_sequence=$orphan_sequence AND status='RESOLVED';")"
  [[ "$resolved" == 1 ]] && break
done
[[ "$resolved" == 1 ]] || fail 'quarantined orphan was not replayed after parent recovery'

audit="$(curl -fsS -X POST "$BASE_URL/internal/v1/payment-shadow/audit")"
[[ "$audit" != *'"status":"MISMATCH"'* ]] || fail 'source/target audit is not clean'
[[ "$(grep -o '"status":"MATCH"' <<<"$audit" | wc -l)" == 14 ]] || fail 'expected 14 table audits'
[[ "$(target_sql "SELECT COUNT(*) FROM payment_shadow_orphan_quarantine WHERE status='OPEN';")" == 0 ]] ||
  fail 'open orphan rows remain'

echo "Payment shadow verification passed: generation $snapshot_generation, watermark $target_sequence, 14 table audits MATCH, orphan replay RESOLVED, business traffic gate closed."

#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
first="$(curl -fsS "$BASE_URL/api/products")"
[[ "$(jq length <<<"$first")" -gt 0 ]] || { echo 'Independent catalog returned no data' >&2; exit 1; }
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"
idem="$(compose exec -T commerce-redis redis-cli --scan --pattern 'commerceflow:idempotency:payment:*' | wc -l | tr -d ' ')"
[[ "$idem" -ge 1 ]] || { echo 'Payment idempotency acceleration key missing' >&2; exit 1; }

redis_stopped=false
restore_redis() { if [[ "$redis_stopped" == true ]]; then compose start commerce-redis >/dev/null; fi; }
trap restore_redis EXIT
compose stop commerce-redis >/dev/null; redis_stopped=true
fallback="$(curl --max-time 15 -fsS "$BASE_URL/api/products")"
[[ "$(jq length <<<"$fallback")" -gt 0 ]] || { echo 'Independent catalog failed while commerce Redis was down' >&2; exit 1; }
compose start commerce-redis >/dev/null; redis_stopped=false
deadline=$((SECONDS + 30)); ping=''
while ((SECONDS < deadline)); do
  sleep 1
  ping="$(compose exec -T commerce-redis redis-cli ping 2>/dev/null | tr -d '\r' || true)"
  [[ "$ping" == PONG ]] && break
done
[[ "$ping" == PONG ]] || { echo 'Redis did not recover' >&2; exit 1; }
echo "Chapter 08 verification passed: payment idempotency keys=$idem, independent catalog during commerce Redis outage=true"

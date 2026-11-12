#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl
BASE_URL="${1:-http://localhost:14200}"
key='commerceflow:catalog:products:v1:en'
compose exec -T commerce-redis redis-cli DEL "$key" >/dev/null
first="$(curl -fsS "$BASE_URL/api/products")"
[[ "$(jq length <<<"$first")" -gt 0 ]] || { echo 'Product database fallback returned no data' >&2; exit 1; }
exists="$(compose exec -T commerce-redis redis-cli EXISTS "$key" | tr -d '\r')"
ttl="$(compose exec -T commerce-redis redis-cli TTL "$key" | tr -d '\r')"
[[ "$exists" == 1 && "$ttl" -ge 1 && "$ttl" -le 300 ]] || { echo "Product cache missing or TTL invalid: exists=$exists ttl=$ttl" >&2; exit 1; }
"$SCRIPT_DIR/verify-payment-notifications.sh" "$BASE_URL"
idem="$(compose exec -T commerce-redis redis-cli --scan --pattern 'commerceflow:idempotency:payment:*' | wc -l | tr -d ' ')"
[[ "$idem" -ge 1 ]] || { echo 'Payment idempotency acceleration key missing' >&2; exit 1; }

redis_stopped=false
restore_redis() { if [[ "$redis_stopped" == true ]]; then compose start commerce-redis >/dev/null; fi; }
trap restore_redis EXIT
compose stop commerce-redis >/dev/null; redis_stopped=true
fallback="$(curl --max-time 15 -fsS "$BASE_URL/api/products")"
[[ "$(jq length <<<"$fallback")" -gt 0 ]] || { echo 'Product fallback failed while Redis was down' >&2; exit 1; }
compose start commerce-redis >/dev/null; redis_stopped=false
deadline=$((SECONDS + 30)); ping=''
while ((SECONDS < deadline)); do
  sleep 1
  ping="$(compose exec -T commerce-redis redis-cli ping 2>/dev/null | tr -d '\r' || true)"
  [[ "$ping" == PONG ]] && break
done
[[ "$ping" == PONG ]] || { echo 'Redis did not recover' >&2; exit 1; }
echo "Chapter 08 verification passed: cache TTL=$ttl, idempotency keys=$idem, MySQL fallback during Redis outage=true"

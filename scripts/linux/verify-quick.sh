#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL='http://localhost:14200';SKIP_BUILD=false
while (($#));do
  case "$1" in
    --base-url) BASE_URL="$2";shift 2;;
    --skip-build) SKIP_BUILD=true;shift;;
    -h|--help) echo 'Usage: verify-quick.sh [--base-url URL] [--skip-build]';exit 0;;
    *) echo "Unknown argument: $1" >&2;exit 2;;
  esac
done

"$SCRIPT_DIR/audit-script-contracts.sh"
compose config --quiet
[[ "$SKIP_BUILD" == true ]]||compose build
compose up -d --wait
"$SCRIPT_DIR/verify-authorization.sh" "$BASE_URL"
"$SCRIPT_DIR/verify-i18n.sh" "$BASE_URL"
"$SCRIPT_DIR/verify-order-cancellation.sh" "$BASE_URL"
"$SCRIPT_DIR/verify-payment-cancellation-race.sh" "$BASE_URL" http://localhost:14201 2 0 20261113 0 true
"$SCRIPT_DIR/verify-refunds.sh" "$BASE_URL" http://localhost:14201
"$SCRIPT_DIR/verify-auto-lifecycle.sh" "$BASE_URL"
"$SCRIPT_DIR/verify-commerce-redis-business.sh" "$BASE_URL"
curl -fsS "$BASE_URL/healthz" >/dev/null
for url in http://localhost:18081/actuator/health http://localhost:18082/actuator/health http://localhost:18083/actuator/health;do
  [[ "$(curl -fsS "$url"|jq -r .status)" == UP ]]||{ echo "Health check failed: $url" >&2;exit 1; }
done
echo 'Quick regression passed: contracts, build/start, auth/i18n, cancellation, payment race, refund, logistics, receipt/completion/review, lease recovery, Redis fallback and health.'

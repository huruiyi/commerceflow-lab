#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL='http://localhost:14200'
SKIP_BUILD=false
while (($#)); do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    -h|--help)
      echo 'Usage: ./scripts/linux/verify-all.sh [--base-url URL] [--skip-build]'
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ "$SKIP_BUILD" == false ]]; then
  echo '==> Building images and running image-build tests'
  compose build
fi
echo '==> Starting the complete stack'
compose up -d --wait
services="$(compose ps --services --filter status=running)"
for service in mysql redis kafka payment-mysql mock-payment-service payment-ui backend web; do
  grep -Fxq "$service" <<<"$services" || { echo "Required service is not running: $service" >&2; exit 1; }
done

for script in verify-i18n.sh verify-authorization.sh verify-payment-outcomes.sh verify-kafka-payment.sh verify-receipt-reviews.sh verify-redis-business.sh; do
  echo
  echo "==> $script"
  "$SCRIPT_DIR/$script" "$BASE_URL"
done

deadline=$((SECONDS + 60)); healthy=false
while ((SECONDS < deadline)); do
  if curl -fsS --max-time 5 "$BASE_URL/healthz" >/dev/null \
    && [[ "$(curl -fsS --max-time 5 http://localhost:18081/actuator/health | jq -r .status)" == UP ]] \
    && [[ "$(curl -fsS --max-time 5 http://localhost:18082/actuator/health | jq -r .status)" == UP ]]; then
    healthy=true; break
  fi
  sleep 2
done
[[ "$healthy" == true ]] || { echo 'Final health check did not recover within 60 seconds' >&2; exit 1; }
echo
echo 'Complete Docker acceptance passed: build, startup, auth, i18n, payment outcomes, Kafka, fulfillment, reviews, Redis fallback and health.'

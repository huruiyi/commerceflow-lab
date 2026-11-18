#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq docker
BASE_URL="${1:-http://localhost:18081}"
MAX_WAIT_SECONDS="${2:-120}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temp_dir="$(new_temp_dir)"

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
  docker compose start catalog-service >/dev/null 2>&1
  wait_healthy fairy-commerceflow-catalog-service >/dev/null 2>&1
  rm -rf -- "$temp_dir"
  exit "$status"
}
trap cleanup EXIT

cd "$PROJECT_ROOT"
docker compose up -d --wait catalog-service commerce-service >/dev/null
state="$(docker compose exec -T catalog-db mysql -N -ucatalog -pcatalog-password catalog \
  -e "SELECT status FROM catalog_cutover_state WHERE cutover_key='catalog-v29-single-owner';" \
  2>/dev/null)"
[[ "$state" == COMPLETE ]]
[[ "$(curl -fsS "$BASE_URL/api/products?lang=zh-CN" | jq -r '.[0].name')" == 学习笔记本电脑 ]]
docker compose --profile catalog-migration run --rm --no-deps catalog-data-migrator

docker compose stop catalog-service >/dev/null
status="$(curl --max-time 10 -sS -o "$temp_dir/outage.json" -w '%{http_code}' \
  "$BASE_URL/api/products")"
[[ "$status" == 503 ]]
[[ "$(curl -fsS "$BASE_URL/actuator/health" | jq -r .status)" == UP ]]

echo 'Catalog single-owner runtime passed: COMPLETE marker, localized proxy, idempotent stale-source guard, stable 503 and core-commerce isolation.'

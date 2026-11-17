#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq

BASE_URL="${1:-http://localhost:14200}"
MAX_WAIT_SECONDS="${2:-90}"
compose up -d --wait order-service commerce-service >/dev/null
order_environment="$(docker inspect fairy-commerceflow-order-service --format '{{range .Config.Env}}{{println .}}{{end}}')"
grep -qx 'ORDER_ACCEPTING_TRAFFIC=true' <<<"$order_environment"
grep -qx 'ORDER_SHADOW_ENABLED=false' <<<"$order_environment"

if compose exec -T commerce-db mysql --user=commerce_runtime --password=commerce-runtime-password \
  commerceflow -e 'SELECT COUNT(*) FROM shop_orders;' >/dev/null 2>&1;then
  echo 'Commerce runtime user can still read the order source table' >&2;exit 1
fi
source_before="$(compose exec -T commerce-db mysql -N --user=root --password=local-root-password \
  commerceflow -e 'SELECT COUNT(*) FROM shop_orders;' 2>/dev/null)"
target_before="$(compose exec -T order-db mysql -N --user=orders --password=orders-password \
  orders -e 'SELECT COUNT(*) FROM shop_orders;' 2>/dev/null)"

temp_dir="$(mktemp -d)";trap 'rm -rf "$temp_dir"' EXIT
jar="$temp_dir/customer.cookies"
csrf="$(curl -fsS -c "$jar" "$BASE_URL/api/auth/csrf")"
header="$(jq -r .headerName <<<"$csrf")";token="$(jq -r .token <<<"$csrf")"
curl -fsS -b "$jar" -c "$jar" -H "$header: $token" -H 'Content-Type: application/json' \
  -X POST "$BASE_URL/api/auth/login" \
  --data '{"email":"demo@example.com","password":"demo-password","rememberMe":false}' >/dev/null
curl -fsS -b "$jar" -H "$header: $token" -H 'Content-Type: application/json' \
  -X POST "$BASE_URL/api/cart/items" \
  --data '{"productId":1,"quantity":1,"note":"order-runtime-cutover"}' >/dev/null
order="$(curl -fsS -b "$jar" -H "$header: $token" -H 'Content-Type: application/json' \
  -X POST "$BASE_URL/api/purchase-orders/checkout" --data '{}')"
order_id="$(jq -r .id <<<"$order")";order_number="$(jq -r .orderNumber <<<"$order")"

source_after="$(compose exec -T commerce-db mysql -N --user=root --password=local-root-password \
  commerceflow -e 'SELECT COUNT(*) FROM shop_orders;' 2>/dev/null)"
target_after="$(compose exec -T order-db mysql -N --user=orders --password=orders-password \
  orders -e 'SELECT COUNT(*) FROM shop_orders;' 2>/dev/null)"
target_row="$(compose exec -T order-db mysql -N --user=orders --password=orders-password orders \
  -e "SELECT COUNT(*) FROM shop_orders WHERE id=$order_id AND order_number='$order_number';" 2>/dev/null)"
[[ "$source_after" == "$source_before" && "$target_after" == "$((target_before+1))" && "$target_row" == 1 ]]||{
  echo "Order ownership audit failed: source=$source_before->$source_after target=$target_before->$target_after row=$target_row" >&2;exit 1;
}

compose stop order-service >/dev/null
status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$jar" "$BASE_URL/api/purchase-orders")"
compose start order-service >/dev/null
deadline=$((SECONDS+MAX_WAIT_SECONDS));until [[ "$(docker inspect fairy-commerceflow-order-service --format '{{.State.Health.Status}}' 2>/dev/null)" == healthy ]];do
  ((SECONDS>=deadline))&&{ echo 'order-service did not recover' >&2;exit 1; };sleep 2
done
[[ "$status" == 503 ]]||{ echo "Expected outage status 503, got $status" >&2;exit 1; }
echo "Order runtime cutover passed: order=$order_id, source=$source_after unchanged, target=$target_after, source access denied, outage=503."

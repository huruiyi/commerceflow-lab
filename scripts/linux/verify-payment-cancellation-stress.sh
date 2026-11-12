#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"
require_commands curl jq
BASE_URL="${1:-http://localhost:14200}"
PAYMENT_BASE_URL="${2:-http://localhost:14201}"
DURATION_SECONDS="${3:-300}"
MINIMUM_ITERATIONS="${4:-30}"
SEED="${5:-20261113}"
PACING_MILLISECONDS="${6:-900}"
INVENTORY_FLOOR="${7:-10000}"
[[ "$INVENTORY_FLOOR" =~ ^[0-9]+$ ]]&&(( INVENTORY_FLOOR >= 100 && INVENTORY_FLOOR <= 100000 ))||{
  echo 'Inventory floor must be between 100 and 100000' >&2;exit 1;
}

temp_dir="$(new_temp_dir)";trap 'rm -rf -- "$temp_dir"' EXIT
merchant_jar="$temp_dir/merchant.cookies"
IFS=$'\t' read -r merchant_header merchant_token <<<"$(storefront_login "$BASE_URL" merchant@example.com "$merchant_jar")"
inventory="$(json_request GET "$BASE_URL/api/merchant/inventory" "$merchant_jar")"
available="$(jq -r '.[]|select(.productId==1)|.stockAvailable'<<<"$inventory")"
[[ -n "$available" ]]||{ echo 'Stress product 1 is not managed by the demo merchant' >&2;exit 1; }
if (( available < INVENTORY_FLOOR ));then
  delta=$((INVENTORY_FLOOR-available))
  json_request POST "$BASE_URL/api/merchant/inventory/1/adjustments" "$merchant_jar" "$merchant_header" "$merchant_token" \
    "$(jq -nc --argjson delta "$delta" '{delta:$delta,reason:"Prepare inventory for payment/cancellation soak test"}')" >/dev/null
  echo "Raised product 1 available inventory by $delta for the soak test."
fi

exec bash "$SCRIPT_DIR/verify-payment-cancellation-race.sh" \
  "$BASE_URL" "$PAYMENT_BASE_URL" "$MINIMUM_ITERATIONS" "$DURATION_SECONDS" "$SEED" "$PACING_MILLISECONDS" true

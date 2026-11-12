#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq
BASE_URL="${1:-http://localhost:14200}"
PAYMENT_BASE_URL="${2:-http://localhost:14201}"
ITERATIONS="${3:-12}"
DURATION_SECONDS="${4:-0}"
SEED="${5:-0}"
PACING_MILLISECONDS="${6:-0}"
AUDIT_DATABASE="${7:-false}"
[[ "$ITERATIONS" =~ ^[0-9]+$ ]] && (( ITERATIONS >= 1 && ITERATIONS <= 10000 )) || {
  echo 'Iterations must be between 1 and 10000' >&2; exit 1;
}
[[ "$DURATION_SECONDS" =~ ^[0-9]+$ ]] && (( DURATION_SECONDS <= 86400 )) || { echo 'Duration must be between 0 and 86400 seconds' >&2;exit 1; }
[[ "$SEED" =~ ^[0-9]+$ ]] || { echo 'Seed must be a non-negative integer' >&2;exit 1; }
[[ "$PACING_MILLISECONDS" =~ ^[0-9]+$ ]]&&(( PACING_MILLISECONDS <= 60000 ))||{ echo 'Pacing must be between 0 and 60000 milliseconds' >&2;exit 1; }

temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
customer_jar="$temp_dir/customer.cookies"
IFS=$'\t' read -r customer_header customer_token <<<"$(storefront_login "$BASE_URL" demo@example.com "$customer_jar")"
selected="$(json_request GET "$BASE_URL/api/addresses/selected" "$customer_jar")"
if [[ "$(jq -r '.addressId // empty' <<<"$selected")" == '' ]]; then
  address="$(json_request POST "$BASE_URL/api/addresses" "$customer_jar" "$customer_header" "$customer_token" '{"recipientName":"Race Tester","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Concurrency Road","addressLine2":"","postalCode":"200120","label":"Race","isDefault":true}')"
  json_request PUT "$BASE_URL/api/addresses/$(jq -r .id <<<"$address")/select" "$customer_jar" "$customer_header" "$customer_token" '{}' >/dev/null
fi

declare -A outcomes=();declare -a order_ids=()
started_at="$(date +%s)";iteration=0
(( SEED > 0 ))&&RANDOM=$((SEED % 32768))
while (( iteration < ITERATIONS || (DURATION_SECONDS > 0 && $(date +%s) - started_at < DURATION_SECONDS) ));do
  iteration=$((iteration+1))
  json_request POST "$BASE_URL/api/cart/items" "$customer_jar" "$customer_header" "$customer_token" '{"productId":1,"quantity":1,"note":"payment-cancellation-race"}' >/dev/null
  order="$(json_request POST "$BASE_URL/api/purchase-orders/checkout" "$customer_jar" "$customer_header" "$customer_token" '{}')"
  order_id="$(jq -r .id <<<"$order")"
  order_ids+=("$order_id")
  payment_body="$(jq -nc --arg key "$(uuid_hex)" '{idempotencyKey:$key,simulateFailure:false,channel:"ALIPAY"}')"
  payment_file="$temp_dir/payment-create.json";pending='';payment_failure=''
  for attempt in $(seq 1 15);do
    payment_code="$(curl -sS -o "$payment_file" -w '%{http_code}' -b "$customer_jar" -c "$customer_jar" \
      -H "$customer_header: $customer_token" -H 'Content-Type: application/json' -X POST \
      "$BASE_URL/api/purchase-orders/$order_id/payments" --data "$payment_body")"
    if [[ "$payment_code" =~ ^2 ]];then pending="$(cat "$payment_file")";break;fi
    if [[ "$payment_code" == 409 ]]&&grep -q '429 Too Many Requests' "$payment_file"&&(( attempt < 15 ));then
      echo "Payment platform rate limit reached; retrying payment creation in 5 seconds ($attempt/15)." >&2;sleep 5;continue
    fi
    payment_failure="Payment creation failed with HTTP $payment_code: $(cat "$payment_file")";break
  done
  if [[ -z "$pending" ]];then
    json_request POST "$BASE_URL/api/purchase-orders/$order_id/cancel" "$customer_jar" "$customer_header" "$customer_token" \
      '{"reason":"Clean up failed stress-test payment setup"}' >/dev/null||true
    echo "${payment_failure:-Payment creation exhausted rate-limit retries}" >&2;exit 1
  fi
  [[ "$(jq -r .payment.status <<<"$pending")" == 'PENDING' ]] || { echo "Iteration $iteration did not create a pending payment" >&2; exit 1; }
  checkout_url="$(jq -r .payment.checkoutUrl <<<"$pending")"
  checkout_token="${checkout_url%%\?*}"; checkout_token="${checkout_token##*/}"

  case $((iteration % 3)) in
    0) cancel_delay=0; payment_delay=250 ;;
    1) cancel_delay=200; payment_delay=0 ;;
    *) cancel_delay=$((RANDOM % 121)); payment_delay=$((RANDOM % 121)) ;;
  esac

  race_dir="$temp_dir/race-$iteration"; mkdir -p "$race_dir"
  gate="$race_dir/go"
  (
    while [[ ! -e "$gate" ]]; do sleep 0.01; done
    sleep "$(printf '0.%03d' "$cancel_delay")"
    curl -sS -o "$race_dir/cancel.json" -w '%{http_code}' -b "$customer_jar" -c "$customer_jar" \
      -H "$customer_header: $customer_token" -H 'Content-Type: application/json' \
      -X POST "$BASE_URL/api/purchase-orders/$order_id/cancel" \
      --data '{"reason":"randomized payment cancellation race"}' >"$race_dir/cancel.code"
  ) & cancel_pid=$!
  (
    while [[ ! -e "$gate" ]]; do sleep 0.01; done
    sleep "$(printf '0.%03d' "$payment_delay")"
    curl -sS -o "$race_dir/payment.json" -w '%{http_code}' \
      -X POST "$PAYMENT_BASE_URL/api/checkout/$checkout_token/complete?success=true" >"$race_dir/payment.code"
  ) & payment_pid=$!
  touch "$gate"
  wait "$cancel_pid" || true
  wait "$payment_pid" || true

  deadline=$((SECONDS + 15))
  while :; do
    resolved="$(json_request GET "$BASE_URL/api/purchase-orders/$order_id" "$customer_jar")"
    order_status="$(jq -r .status <<<"$resolved")"
    [[ "$order_status" != 'PENDING_PAYMENT' && "$order_status" != 'CANCELLING' ]] && break
    (( SECONDS >= deadline )) && break
    sleep 0.2
  done
  payment_status="$(jq -r '.payment.status // "NONE"' <<<"$resolved")"
  valid=false
  case "$order_status/$payment_status" in
    CANCELLED/CLOSED|PAID/SUCCESS) valid=true ;;
    REFUNDING/SUCCESS) jq -e '.refund != null' <<<"$resolved" >/dev/null && valid=true ;;
    REFUNDED/SUCCESS) jq -e '.refund.status == "SUCCEEDED"' <<<"$resolved" >/dev/null && valid=true ;;
  esac
  if [[ "$valid" != true ]]; then
    echo "Iteration $iteration is inconsistent: order=$order_status payment=$payment_status cancel-http=$(cat "$race_dir/cancel.code") payment-http=$(cat "$race_dir/payment.code")" >&2
    exit 1
  fi
  key="$order_status/$payment_status"; outcomes[$key]=$(( ${outcomes[$key]:-0} + 1 ))
  if (( DURATION_SECONDS > 0 ));then progress="$iteration / $(( $(date +%s)-started_at ))s";else progress="$iteration/$ITERATIONS";fi
  echo "[$progress] order=$(jq -r .orderNumber <<<"$resolved") outcome=$key delays=${cancel_delay}ms/${payment_delay}ms"
  (( PACING_MILLISECONDS > 0 ))&&sleep "$(printf '%d.%03d' $((PACING_MILLISECONDS/1000)) $((PACING_MILLISECONDS%1000)))"
done

if (( DURATION_SECONDS > 0 && ${#outcomes[@]} < 2 ));then
  echo 'Stress run did not observe both payment-wins and cancellation-wins terminal families' >&2;exit 1
fi

if [[ "$AUDIT_DATABASE" == true ]];then
  ids="$(IFS=,;echo "${order_ids[*]}")"
  database_user="${DATABASE_USERNAME:-commerceflow}";database_password="${DATABASE_PASSWORD:-commerceflow-password}"
  query="SELECT COUNT(DISTINCT o.id),COUNT(p.id),SUM(CASE WHEN (o.status='CANCELLED' AND p.status='CLOSED') OR (o.status IN ('PAID','REFUNDING','REFUNDED') AND p.status='SUCCESS') THEN 0 ELSE 1 END) FROM shop_orders o LEFT JOIN payment_records p ON p.order_id=o.id WHERE o.id IN ($ids);"
  audit="$(docker compose --project-directory "$PROJECT_ROOT" exec -T commerce-db mysql -N "-u$database_user" "-p$database_password" commerceflow -e "$query" 2>/dev/null)"
  read -r orders_count payments_count contradictions <<<"$audit"
  [[ "$orders_count" == "$iteration" && "$payments_count" == "$iteration" && "$contradictions" == 0 ]]||{
    echo "Docker MySQL consistency audit failed: $orders_count/$payments_count/$contradictions" >&2;exit 1;
  }
fi

summary=''
for key in "${!outcomes[@]}"; do summary+="${summary:+, }$key=${outcomes[$key]}"; done
elapsed=$(( $(date +%s)-started_at ))
echo "Payment/cancellation race verification passed: iterations=$iteration; elapsed=${elapsed}s; seed=$SEED; pacing=${PACING_MILLISECONDS}ms; databaseAudit=$AUDIT_DATABASE; $summary"

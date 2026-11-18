#!/usr/bin/env bash
set -euo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
database_user="${DATABASE_USER:-root}"
database_password="${DATABASE_PASSWORD:-local-root-password}"
source_dir="$PROJECT_ROOT/services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/fulfillment"

services="$(compose config --services)"
if grep -Eq '^(fulfillment-service|fulfillment-db|.*postgres.*)$' <<< "$services"; then
  echo 'Unexpected fulfillment or PostgreSQL service' >&2
  exit 1
fi
imports="$(grep -RhE --include='*.java' 'import .*\.infrastructure\.' "$source_dir" || true)"
if grep -qEv '^\s*import vip\.fairy\.commerceflow\.commerce\.fulfillment\.infrastructure\.|^$' <<< "$imports"; then
  echo 'Fulfillment imports infrastructure owned by another boundary' >&2
  exit 1
fi
logistics_imports="$(grep -RhE --include='*.java' 'import .*\.commerce\.logistics\.' "$source_dir" || true)"
if grep -qEv '\.logistics\.application\.contract\.LogisticsFulfillmentV1[.;]|^$' <<< "$logistics_imports"; then
  echo 'Fulfillment must only call logistics through LogisticsFulfillmentV1' >&2
  exit 1
fi

result="$(compose exec -T commerce-db mysql -N -B "--user=$database_user" \
  "--password=$database_password" commerceflow < "$PROJECT_ROOT/scripts/audit-fulfillment-admission.sql")"
order_result="$(compose exec -T order-db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B --user="$MYSQL_USER" "$MYSQL_DATABASE"' \
  < "$PROJECT_ROOT/scripts/audit-fulfillment-order-intents.sql")"
declare -A metrics
while read -r key value extra; do
  if [[ ! "$key" =~ ^[A-Z_]+$ || ! "$value" =~ ^[0-9]+$ || -n "$extra" || -v 'metrics[$key]' ]]; then
    echo "Unrecognized or duplicate audit metric: $key $value $extra" >&2
    exit 1
  fi
  metrics["$key"]="$value"
  printf '%s\t%s\n' "$key" "$value"
done <<< "$result"$'\n'"$order_result"

for expected in FULFILLMENT_TABLES:6 FULFILLMENT_CROSS_BOUNDARY_FKS:0 FULFILLMENT_INVALID_LEASES:0 \
  FULFILLMENT_COMMAND_REGISTRY_MISMATCHES:0 \
  FULFILLMENT_UNRESOLVED_RECOVERY_REFERENCES:0 LOGISTICS_TABLES:4 LOGISTICS_CROSS_BOUNDARY_FKS:0 \
  LOGISTICS_COMMAND_STATE_MISMATCHES:0 LOGISTICS_RECEIPT_MISMATCHES:0 \
  UNRESOLVED_NOTIFICATION_REFERENCES:0 ORDER_SHIPMENT_INTENTS_INVALID_STATE:0; do
  key="${expected%:*}"
  if [[ "${metrics[$key]:-missing}" != "${expected#*:}" ]]; then
    echo "Invalid baseline: $key" >&2
    exit 1
  fi
done
has_backlog=false
for key in FULFILLMENT_PENDING FULFILLMENT_REVIEW_REQUIRED FULFILLMENT_ACTIVE_LEASES \
  FULFILLMENT_EXPIRED_LEASES LOGISTICS_COMMANDS_PENDING \
  FULFILLMENT_ORDERS_WITH_MULTIPLE_OPEN_JOBS UNATTACHED_SHIPMENTS ORDER_SHIPMENT_INTENTS_OPEN; do
  if [[ ! -v 'metrics[$key]' ]]; then
    echo "Missing backlog metric: $key" >&2
    exit 1
  fi
  if (( metrics[$key] > 0 )); then
    has_backlog=true
    printf 'Backlog: %s=%s\n' "$key" "${metrics[$key]}"
  fi
done
if [[ "$has_backlog" == true ]]; then
  echo 'Fulfillment admission: REJECTED (fulfillment or logistics backlog remains).' >&2
  exit 1
else
  echo 'Fulfillment admission audit: PASSED (read-only checks).'
fi

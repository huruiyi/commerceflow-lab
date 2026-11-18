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
if grep -REq --include='*.java' 'import .*\.infrastructure\.' "$source_dir"; then
  echo 'Fulfillment imports infrastructure directly' >&2
  exit 1
fi

result="$(compose exec -T commerce-db mysql -N -B "--user=$database_user" \
  "--password=$database_password" commerceflow < "$PROJECT_ROOT/scripts/audit-fulfillment-admission.sql")"
printf '%s\n' "$result"
for expected in 'LOGISTICS_TABLES 3' 'LOGISTICS_CROSS_BOUNDARY_FKS 0' 'UNRESOLVED_NOTIFICATION_REFERENCES 0'; do
  if ! grep -Fxq "$expected" <<< "${result//$'\t'/ }"; then
    echo "Missing baseline: $expected" >&2
    exit 1
  fi
done
echo 'CREATE admission: REJECTED (durable workflow and downstream command gates remain pending).'

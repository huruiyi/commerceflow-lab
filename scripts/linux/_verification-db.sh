#!/usr/bin/env bash

verify_db() {
  local scope="$1" query="$2" runner="${3:-}" host schema user password root
  case "$scope" in
    commerce) host=commerce-db schema=commerceflow user=commerce_audit password="${COMMERCE_AUDIT_PASSWORD:-local-commerce-audit}" ;;
    legacy) host=commerce-db schema=commerceflow user=commerce_legacy_audit password="${COMMERCE_LEGACY_AUDIT_PASSWORD:-local-legacy-audit}" ;;
    task) host=commerce-db schema=commerceflow user=commerce_task_fixture password="${COMMERCE_TASK_FIXTURE_PASSWORD:-local-task-fixture}" ;;
    recovery) host=commerce-db schema=commerceflow user=commerce_recovery_fixture password="${COMMERCE_RECOVERY_FIXTURE_PASSWORD:-local-recovery-fixture}" ;;
    lock) host=commerce-db schema=commerceflow user=commerce_legacy_lock password="${COMMERCE_LEGACY_LOCK_PASSWORD:-local-legacy-lock}" ;;
    order) host=order-db schema=orders user=order_audit password="${ORDER_AUDIT_PASSWORD:-local-order-audit}" ;;
    payment) host=payment-db schema=payments user=payment_audit password="${PAYMENT_AUDIT_PASSWORD:-local-payment-audit}" ;;
    *) echo "Unknown verification database scope: $scope" >&2; return 2 ;;
  esac
  local -a args=(exec -T -e "MYSQL_PWD=$password" "$host" mysql --protocol=TCP --host=127.0.0.1
    --batch --skip-column-names --user="$user" --database="$schema" --execute="$query")
  if [[ -n "$runner" ]]; then
    "$runner" "${args[@]}" | tr -d '\r'
  elif declare -F compose >/dev/null; then
    compose "${args[@]}" | tr -d '\r'
  else
    root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
    docker compose --project-directory "$root" "${args[@]}" | tr -d '\r'
  fi
}

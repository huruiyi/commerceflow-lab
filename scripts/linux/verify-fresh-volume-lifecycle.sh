#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands docker curl jq openssl

BASE_URL='http://localhost:14200'
TEMPORARY_PROJECT_NAME="commerceflow-fresh-bash-$$-$(date +%s)"
REMOVE_TEMPORARY_VOLUMES=false
SKIP_BUILD=false
while (($#)); do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --temporary-project-name) TEMPORARY_PROJECT_NAME="$2"; shift 2 ;;
    --remove-temporary-volumes) REMOVE_TEMPORARY_VOLUMES=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    -h|--help)
      echo 'Usage: verify-fresh-volume-lifecycle.sh [--base-url URL] [--temporary-project-name NAME] [--remove-temporary-volumes] [--skip-build]'
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$TEMPORARY_PROJECT_NAME" =~ ^commerceflow-fresh-[a-z0-9-]+$ ]] || { echo 'Temporary project name must start with commerceflow-fresh-' >&2; exit 2; }

BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://localhost:18081}"
PAYMENT_HEALTH_URL="${PAYMENT_HEALTH_URL:-http://localhost:18082}"
LOGISTICS_HEALTH_URL="${LOGISTICS_HEALTH_URL:-http://localhost:18083}"
mapfile -t default_running < <(compose ps --services --filter status=running)
existing="$(docker ps -a --filter "label=com.docker.compose.project=$TEMPORARY_PROJECT_NAME" --format '{{.ID}}')"
[[ -z "$existing" ]] || { echo "Temporary project already exists: $TEMPORARY_PROJECT_NAME" >&2; exit 1; }
if [[ -v COMPOSE_PROJECT_NAME ]]; then saved_project_present=true; saved_project="$COMPOSE_PROJECT_NAME"; else saved_project_present=false; saved_project=''; fi
passed=false

temp_compose() {
  docker compose --project-directory "$PROJECT_ROOT" --project-name "$TEMPORARY_PROJECT_NAME" "$@"
}

store_sql() {
  temp_compose exec -T commerce-db mysql -ucommerceflow -pcommerceflow-password -Nse "$1" commerceflow 2>/dev/null | tail -n1 | tr -d '\r'
}

payment_sql() {
  temp_compose exec -T payment-simulator-db mysql -upayment -ppayment-password -Nse "$1" payment_platform 2>/dev/null | tail -n1 | tr -d '\r'
}

logistics_sql() {
  temp_compose exec -T logistics-simulator-db mysql -ulogistics -plogistics-password -Nse "$1" logistics_platform 2>/dev/null | tail -n1 | tr -d '\r'
}

cleanup() {
  local status=$?
  set +e
  if [[ "$passed" != true ]]; then temp_compose logs --no-color --tail 200 payment-simulator-service payment-simulator-db commerce-service logistics-simulator-service; fi
  if [[ "$REMOVE_TEMPORARY_VOLUMES" == true ]]; then temp_compose down --volumes --remove-orphans; else temp_compose down --remove-orphans; fi
  if [[ "$saved_project_present" == true ]]; then export COMPOSE_PROJECT_NAME="$saved_project"; else unset COMPOSE_PROJECT_NAME; fi
  if ((${#default_running[@]})); then compose --profile rabbitmq --profile rocketmq up -d --wait "${default_running[@]}"; fi
  exit "$status"
}
trap cleanup EXIT

if ((${#default_running[@]})); then compose stop; fi
export COMPOSE_PROJECT_NAME="$TEMPORARY_PROJECT_NAME"
if [[ "$SKIP_BUILD" == false ]]; then temp_compose build; fi
temp_compose up -d --wait
volume_count="$(docker volume ls --filter "label=com.docker.compose.project=$TEMPORARY_PROJECT_NAME" --format '{{.Name}}' | wc -l | tr -d ' ')"
(( volume_count >= 5 )) || { echo "Fresh project created only $volume_count volumes" >&2; exit 1; }

migration_audit="$(store_sql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL")"
[[ "$migration_audit" == '23|0|23' ]] || { echo "Unexpected storefront Flyway audit: $migration_audit" >&2; exit 1; }
payment_migration_audit="$(payment_sql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL")"
[[ "$payment_migration_audit" == '3|0|3' ]] || { echo "Unexpected payment Flyway audit: $payment_migration_audit" >&2; exit 1; }
logistics_migration_audit="$(logistics_sql "SELECT CONCAT(COUNT(*),'|',SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),'|',MAX(CAST(version AS UNSIGNED))) FROM flyway_schema_history WHERE version IS NOT NULL")"
[[ "$logistics_migration_audit" == '4|0|4' ]] || { echo "Unexpected logistics Flyway audit: $logistics_migration_audit" >&2; exit 1; }
seed_audit="$(store_sql "SELECT CONCAT((SELECT COUNT(*) FROM app_users),'|',(SELECT COUNT(*) FROM products),'|',(SELECT COUNT(*) FROM shop_orders))")"
[[ "$seed_audit" == '6|6|0' ]] || { echo "Fresh seed audit failed: $seed_audit" >&2; exit 1; }

bash "$SCRIPT_DIR/verify-auto-lifecycle.sh" "$BASE_URL"
business_audit="$(store_sql "SELECT CONCAT((SELECT COUNT(*) FROM shop_orders WHERE status='COMPLETED'),'|',(SELECT COUNT(*) FROM product_reviews),'|',(SELECT COUNT(*) FROM shipment_records WHERE status='DELIVERED'),'|',(SELECT COUNT(*) FROM scheduled_tasks WHERE task_type='AUTO_COMPLETE' AND status='SUCCEEDED'),'|',(SELECT COUNT(*) FROM order_events WHERE event_type='ORDER_AUTO_COMPLETED'))")"
IFS='|' read -r completed reviews delivered complete_tasks complete_events <<<"$business_audit"
(( completed >= 1 && reviews >= 1 && delivered >= 1 && complete_tasks >= 1 && complete_events >= 1 )) || { echo "Fresh business audit failed: $business_audit" >&2; exit 1; }
for health_url in "$BACKEND_HEALTH_URL" "$PAYMENT_HEALTH_URL" "$LOGISTICS_HEALTH_URL"; do
  [[ "$(curl -fsS "$health_url/actuator/health" | jq -r .status)" == UP ]] || { echo "Fresh project health failed: $health_url" >&2; exit 1; }
done
passed=true
echo "Fresh-volume lifecycle passed: project=$TEMPORARY_PROJECT_NAME, Flyway storefront/payment/logistics=V23/V3/V4, empty seed order set, login -> checkout -> payment -> fulfillment -> logistics -> receipt -> completion -> review, audit=$business_audit."

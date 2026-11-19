#!/bin/sh
set -eu

root_sql() {
  case "$1" in
    commerce-db) root_password=$COMMERCE_ROOT_PASSWORD ;;
    order-db) root_password=$ORDER_ROOT_PASSWORD ;;
    payment-db) root_password=$PAYMENT_ROOT_PASSWORD ;;
    *) exit 2 ;;
  esac
  MYSQL_PWD="$root_password" mysql -h "$1" -uroot --protocol=tcp -N -B
}

configure() {
  host=$1 account=$2 password=$3
  case "$password" in
    ''|*[!A-Za-z0-9_.-]*) echo "Invalid verification password for $account" >&2; exit 1 ;;
  esac
  {
    printf "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s' ACCOUNT LOCK;\n" "$account" "$password"
    printf "ALTER USER '%s'@'%%' ACCOUNT LOCK;\nREVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%%';\n" "$account" "$account"
    while IFS= read -r grant; do
      printf "GRANT %s TO '%s'@'%%';\n" "$grant" "$account"
    done
    printf "ALTER USER '%s'@'%%' IDENTIFIED BY '%s' ACCOUNT UNLOCK;\n" "$account" "$password"
  } | root_sql "$host"
}

case "${1:---readers}" in
  --readers)
    configure commerce-db commerce_audit "$COMMERCE_AUDIT_PASSWORD" <<'SQL'
SELECT (id,email) ON commerceflow.app_users
SELECT (command_id,status,created_at) ON commerceflow.inventory_command_delivery_outbox
SELECT (id,task_type,task_key,status,trigger_publish_attempts,trigger_last_error,last_trigger_source,primary_trigger_snapshot) ON commerceflow.scheduled_tasks
SELECT (task_id,event_type) ON commerceflow.scheduled_task_events
SELECT (id,order_id,merchant_shipment_no,platform_shipment_no,carrier,tracking_number,status,shipped_at,delivered_at) ON commerceflow.shipment_records
SELECT (shipment_id,event_id) ON commerceflow.shipment_events
SELECT (shipment_id,notification_id,processing_status) ON commerceflow.shipment_notifications
SELECT (id,order_item_id,product_id,customer_id,rating,comment,created_at) ON commerceflow.product_reviews
SQL
    configure order-db order_audit "$ORDER_AUDIT_PASSWORD" <<'SQL'
SELECT (id,order_number,customer_id,status,total_amount,currency,confirmed_at,shipped_at,carrier,tracking_number,payment_due_at,cancelled_at,cancel_reason,received_at,completed_at,logistics_delivered_at) ON orders.shop_orders
SELECT (order_id,event_type) ON orders.order_events
SQL
    configure payment-db payment_audit "$PAYMENT_AUDIT_PASSWORD" <<'SQL'
SELECT (id,order_id,payment_number,amount,status,channel,currency,channel_transaction_no,paid_at,expires_at,refunded_amount) ON payments.payment_records
SQL
    ;;
  --legacy-reader)
    root_sql commerce-db <<'SQL'
CREATE DATABASE IF NOT EXISTS commerce_verification;
CREATE TABLE IF NOT EXISTS commerce_verification.schema_keys (
  table_schema VARCHAR(64), table_name VARCHAR(64), referenced_table_name VARCHAR(64)
);
CREATE TABLE IF NOT EXISTS commerce_verification.schema_tables (
  table_schema VARCHAR(64), table_name VARCHAR(64), table_comment TEXT
);
CREATE TABLE IF NOT EXISTS commerce_verification.schema_columns (
  table_schema VARCHAR(64), table_name VARCHAR(64), column_comment TEXT
);
START TRANSACTION;
DELETE FROM commerce_verification.schema_keys;
INSERT INTO commerce_verification.schema_keys
SELECT table_schema,table_name,referenced_table_name FROM information_schema.key_column_usage
WHERE table_schema='commerceflow';
DELETE FROM commerce_verification.schema_tables;
INSERT INTO commerce_verification.schema_tables
SELECT table_schema,table_name,table_comment FROM information_schema.tables
WHERE table_schema='commerceflow';
DELETE FROM commerce_verification.schema_columns;
INSERT INTO commerce_verification.schema_columns
SELECT table_schema,table_name,column_comment FROM information_schema.columns
WHERE table_schema='commerceflow';
COMMIT;
SQL
    existing=$(printf '%s\n' "SELECT table_name FROM information_schema.tables WHERE table_schema='commerceflow';" |
      root_sql commerce-db)
    {
      for table in flyway_schema_history products shop_orders order_items order_shipping_addresses order_events \
        order_process_managers order_command_outbox order_result_inbox product_inventory inventory_reservations \
        inventory_movements inventory_command_inbox inventory_catalog_event_inbox inventory_command_delivery_outbox \
        inventory_command_result_inbox refund_records payment_records payment_notifications payment_events \
        payment_outbox kafka_consumed_event shipment_records product_reviews scheduled_tasks scheduled_task_events; do
        if printf '%s\n' "$existing" | grep -Fxq "$table"; then
          printf 'SELECT ON commerceflow.%s\n' "$table"
        fi
      done
      printf 'SELECT (id,email) ON commerceflow.app_users\n'
      printf 'SELECT ON commerce_verification.%s\n' schema_keys schema_tables schema_columns
    } | configure commerce-db commerce_legacy_audit "$COMMERCE_LEGACY_AUDIT_PASSWORD"
    ;;
  --task-fixture)
    configure commerce-db commerce_task_fixture "$COMMERCE_TASK_FIXTURE_PASSWORD" <<'SQL'
SELECT (id,task_type,task_key) ON commerceflow.scheduled_tasks
UPDATE (due_at,status,completed_at,lease_until,claimed_by,last_error) ON commerceflow.scheduled_tasks
SQL
    ;;
  --recovery-fixture)
    configure commerce-db commerce_recovery_fixture "$COMMERCE_RECOVERY_FIXTURE_PASSWORD" <<'SQL'
SELECT (id,task_type,aggregate_type,aggregate_id,payload_json,policy_version_snapshot) ON commerceflow.scheduled_tasks
INSERT (task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot,attempts,lease_until,claimed_by,version,created_at,updated_at) ON commerceflow.scheduled_tasks
SQL
    ;;
  --legacy-lock)
    case "$VERIFICATION_PROJECT" in
      commerceflow-legacy-?*) ;;
      *) echo 'Legacy row locks require an isolated commerceflow-legacy-* project' >&2; exit 1 ;;
    esac
    configure commerce-db commerce_legacy_lock "$COMMERCE_LEGACY_LOCK_PASSWORD" <<'SQL'
SELECT (id), UPDATE (status) ON commerceflow.shop_orders
SELECT (id), UPDATE (status) ON commerceflow.payment_records
SQL
    ;;
  --retire)
    exists=$(printf "%s\n" "SELECT EXISTS(SELECT 1 FROM mysql.user WHERE User='commerceflow' AND Host='%');" | root_sql commerce-db)
    if [ "$exists" = 1 ]; then
      ready=$(printf "%s\n" "SELECT COUNT(*)=2 AND NOT EXISTS (
        SELECT 1 FROM information_schema.PROCESSLIST WHERE USER='commerceflow'
      ) FROM mysql.user WHERE User IN ('commerce_audit','commerce_legacy_audit') AND Host='%' AND account_locked='N';" |
        root_sql commerce-db)
      if [ "$ready" != 1 ]; then
        echo 'Retirement requires both verification readers and no active commerceflow sessions' >&2
        exit 1
      fi
      printf "%s\n" "ALTER USER 'commerceflow'@'%' ACCOUNT LOCK;" \
        "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'commerceflow'@'%';" | root_sql commerce-db
      echo 'Locked commerceflow@% and revoked its direct privileges'
    fi
    ;;
  *) echo "Unknown verification access mode: $1" >&2; exit 2 ;;
esac

#!/bin/sh
set -eu

case "${RUNTIME_DATABASE_USERNAME}" in
  ''|root|*[!A-Za-z0-9_]* )
    echo "RUNTIME_DATABASE_USERNAME must contain only letters, digits, or underscores" >&2
    exit 1
    ;;
esac

owner=${FULFILLMENT_SOURCE_OWNER:-COMMERCE}
case "$owner" in
  COMMERCE) fulfillment_grants=24 ;;
  FULFILLMENT) fulfillment_grants=0 ;;
  *) echo "FULFILLMENT_SOURCE_OWNER must be COMMERCE or FULFILLMENT" >&2; exit 1 ;;
esac

fulfillment_tables="'fulfillment_order_locks','fulfillment_command_ids','fulfillment_shipment_jobs',
  'fulfillment_shipment_recoveries','fulfillment_merchant_jobs','fulfillment_merchant_recoveries'"
retired_source_tables="'products','product_translations','catalog_event_outbox','customer_orders','retired_customer_orders',
  'customer_notifications','notification_inbox','notification_projection_windows',
  'notification_projection_shadow','notification_projection_audits',
  'order_shadow_change_clock','payment_shadow_change_clock','payment_shadow_change_log'"
escaped_password=$(printf '%s' "${RUNTIME_DATABASE_PASSWORD}" | sed "s/'/''/g")
root_mysql() {
  MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --protocol=tcp --batch --skip-column-names "$@"
}

grant_statements=$(root_mysql -e "
  SELECT CONCAT(
    'GRANT SELECT, INSERT, UPDATE',
    CASE WHEN table_name IN ('order_cancellation_completion','cart_repurchase_requests')
      THEN '' ELSE ', DELETE' END,
    ' ON \`commerceflow\`.\`',
    table_name,
    '\` TO ''${RUNTIME_DATABASE_USERNAME}''@''%'';'
  )
  FROM information_schema.tables
  WHERE table_schema = 'commerceflow'
    AND table_type = 'BASE TABLE'
    AND ('$owner' = 'COMMERCE' OR table_name NOT IN ($fulfillment_tables))
    AND table_name NOT IN ($retired_source_tables)
    AND table_name NOT IN (
      'flyway_schema_history',
      'fulfillment_shadow_clock',
      'fulfillment_shadow_changes',
      'fulfillment_write_admission',
      'product_inventory',
      'inventory_reservations',
      'inventory_movements',
      'inventory_command_inbox',
      'inventory_catalog_event_inbox',
      'shop_orders',
      'order_items',
      'order_shipping_addresses',
      'order_events',
      'order_process_managers',
      'order_command_outbox',
      'order_result_inbox',
      'order_shadow_change_log',
      'order_shadow_clock',
      'payment_records',
      'payment_merchant_accounts',
      'payment_notifications',
      'payment_events',
      'payment_idempotency_locks',
      'payment_reconciliation_issue',
      'payment_outbox',
      'kafka_consumed_event',
      'payment_process_managers',
      'payment_command_outbox',
      'payment_result_inbox',
      'refund_records',
      'refund_events',
      'refund_notifications'
    )
  ORDER BY table_name;
")

if [ -z "${grant_statements}" ]; then
  echo "No commerce runtime tables were found after migration" >&2
  exit 1
fi

if ! root_mysql commerceflow <<SQL
-- A failed assertion inserts the existing sentinel and aborts this mysql batch.
CREATE TEMPORARY TABLE grant_guard (verified INT PRIMARY KEY);
INSERT INTO grant_guard VALUES (0);
INSERT INTO grant_guard VALUES (COALESCE(GET_LOCK('commerceflow.fulfillment-source-owner', 30), 0));
INSERT INTO grant_guard
  SELECT IF(EXISTS (
    SELECT 1 FROM fulfillment_write_admission
    WHERE singleton_id = 1 AND write_owner = '$owner' AND ('$owner' = 'COMMERCE' OR frozen = TRUE)
  ), 2, 0);
INSERT INTO grant_guard
  SELECT IF('$owner' = 'COMMERCE' OR (
    @@GLOBAL.mandatory_roles = ''
    AND NOT EXISTS (SELECT 1 FROM mysql.role_edges WHERE TO_USER = '${RUNTIME_DATABASE_USERNAME}')
    AND NOT EXISTS (SELECT 1 FROM mysql.user WHERE User = '${RUNTIME_DATABASE_USERNAME}' AND Host <> '%')
  ), 3, 0);

CREATE USER IF NOT EXISTS '${RUNTIME_DATABASE_USERNAME}'@'%' IDENTIFIED BY '${escaped_password}';
ALTER USER '${RUNTIME_DATABASE_USERNAME}'@'%' IDENTIFIED BY '${escaped_password}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${RUNTIME_DATABASE_USERNAME}'@'%';
$grant_statements
GRANT SELECT ON commerceflow.fulfillment_write_admission TO '${RUNTIME_DATABASE_USERNAME}'@'%';

INSERT INTO grant_guard
  SELECT IF(COUNT(*) = $fulfillment_grants, 4, 0)
  FROM information_schema.table_privileges
  WHERE grantee = CONCAT('''', '${RUNTIME_DATABASE_USERNAME}', '''@''%''')
    AND table_schema = 'commerceflow' AND table_name IN ($fulfillment_tables);
INSERT INTO grant_guard
  SELECT IF(COUNT(*) = 0, 5, 0)
  FROM information_schema.table_privileges
  WHERE grantee = CONCAT('''', '${RUNTIME_DATABASE_USERNAME}', '''@''%''')
    AND table_schema = 'commerceflow' AND table_name IN ($retired_source_tables);
DO RELEASE_LOCK('commerceflow.fulfillment-source-owner');
SQL
then
  echo "Could not apply grants for fulfillment owner $owner; verify the frozen source marker and runtime account" >&2
  exit 1
fi

for source_table in \
  product_inventory \
  inventory_reservations \
  inventory_movements \
  inventory_command_inbox \
  inventory_catalog_event_inbox \
  shop_orders \
  order_items \
  order_shipping_addresses \
  order_events \
  order_process_managers \
  order_command_outbox \
  order_result_inbox \
  order_shadow_change_log \
  order_shadow_clock \
  payment_records \
  payment_merchant_accounts \
  payment_notifications \
  payment_events \
  payment_idempotency_locks \
  payment_reconciliation_issue \
  payment_outbox \
  kafka_consumed_event \
  payment_process_managers \
  payment_command_outbox \
  payment_result_inbox \
  refund_records \
  refund_events \
  refund_notifications
do
  forbidden=$(root_mysql -e "
    SELECT COUNT(*)
    FROM information_schema.table_privileges
    WHERE grantee = CONCAT('''', '${RUNTIME_DATABASE_USERNAME}', '''@''%''')
      AND table_schema = 'commerceflow'
      AND table_name = '${source_table}';
  ")
  if [ "${forbidden}" != "0" ]; then
    echo "Runtime user still has privileges on ${source_table}" >&2
    exit 1
  fi
done

echo "Commerce runtime grants exclude all inventory, order, payment, and refund source tables."
echo "Commerce runtime grants exclude retired catalog, notification, and shadow tables."
echo "Fulfillment source owner: $owner; six-table direct grants: $fulfillment_grants."

#!/bin/sh
set -eu

case "${SHADOW_PROJECT:-}" in
  fairy-commerceflow-shadow-?*) ;;
  *) echo 'Legacy shadow access requires an isolated fairy-commerceflow-shadow-* project' >&2; exit 1 ;;
esac

order_tables='shop_orders order_items order_shipping_addresses order_events order_process_managers
  order_command_outbox order_result_inbox order_shadow_change_clock order_shadow_change_log'
payment_tables='payment_merchant_accounts payment_records payment_notifications payment_events
  payment_idempotency_locks payment_reconciliation_issue payment_outbox kafka_consumed_event
  refund_records refund_events refund_notifications payment_process_managers payment_command_outbox
  payment_result_inbox payment_shadow_change_clock payment_shadow_change_log'

grant_reader() {
  user=$1 password=$2 tables=$3
  case "$password" in
    ''|*[!A-Za-z0-9_.-]*) echo "Invalid rehearsal password for $user" >&2; exit 1 ;;
  esac

  {
    printf "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s' ACCOUNT LOCK;\n" "$user" "$password"
    printf "ALTER USER '%s'@'%%' ACCOUNT LOCK;\n" "$user"
    printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%%';\n" "$user"
    for table in $tables; do
      printf "GRANT SELECT ON commerceflow.%s TO '%s'@'%%';\n" "$table" "$user"
    done
    printf "ALTER USER '%s'@'%%' IDENTIFIED BY '%s' ACCOUNT UNLOCK;\n" "$user" "$password"
  } | MYSQL_PWD="$COMMERCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --protocol=tcp
}

grant_reader order_shadow_reader "$ORDER_SHADOW_READER_PASSWORD" "$order_tables"
grant_reader payment_shadow_reader "$PAYMENT_SHADOW_READER_PASSWORD" "$payment_tables"

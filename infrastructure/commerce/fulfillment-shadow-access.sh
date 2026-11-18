#!/bin/sh
set -eu

case "${SHADOW_PROJECT:-}" in
  fairy-commerceflow-shadow-?*) ;;
  *) echo 'Fulfillment shadow access requires an isolated fairy-commerceflow-shadow-* project' >&2; exit 1 ;;
esac

case "$FULFILLMENT_READER_PASSWORD" in
  ''|*[!A-Za-z0-9_.-]*)
    echo 'FULFILLMENT_READER_PASSWORD permits letters, digits, underscore, dot and hyphen' >&2
    exit 1
    ;;
esac

owner=$(MYSQL_PWD="$COMMERCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --protocol=tcp \
  -N -B commerceflow -e 'SELECT write_owner FROM fulfillment_write_admission WHERE singleton_id=1;')
if [ "$owner" != COMMERCE ]; then
  echo 'Fulfillment shadow readers are available only on a commerce-owned rehearsal source' >&2
  exit 1
fi

MYSQL_PWD="$COMMERCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --protocol=tcp <<SQL
CREATE USER IF NOT EXISTS 'fulfillment_shadow'@'%' IDENTIFIED BY '$FULFILLMENT_READER_PASSWORD';
ALTER USER 'fulfillment_shadow'@'%' IDENTIFIED BY '$FULFILLMENT_READER_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'fulfillment_shadow'@'%';
GRANT SELECT ON commerceflow.fulfillment_shadow_rows TO 'fulfillment_shadow'@'%';
GRANT SELECT ON commerceflow.fulfillment_shadow_clock TO 'fulfillment_shadow'@'%';
GRANT SELECT ON commerceflow.fulfillment_shadow_changes TO 'fulfillment_shadow'@'%';
ALTER USER 'fulfillment_shadow'@'%' ACCOUNT UNLOCK;
SQL

MYSQL_PWD="$ORDER_ROOT_PASSWORD" mysql -h order-db -uroot --protocol=tcp <<SQL
CREATE USER IF NOT EXISTS 'fulfillment_shadow'@'%' IDENTIFIED BY '$FULFILLMENT_READER_PASSWORD';
ALTER USER 'fulfillment_shadow'@'%' IDENTIFIED BY '$FULFILLMENT_READER_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'fulfillment_shadow'@'%';
GRANT SELECT (id) ON orders.shop_orders TO 'fulfillment_shadow'@'%';
GRANT SELECT (order_id, merchant_id) ON orders.order_items TO 'fulfillment_shadow'@'%';
ALTER USER 'fulfillment_shadow'@'%' ACCOUNT UNLOCK;
SQL

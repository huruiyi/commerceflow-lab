#!/bin/sh
set -eu

revoke_readers() {
  host=$1 password=$2
  shift 2
  for user do
    exists=$(MYSQL_PWD="$password" mysql -h "$host" -uroot --protocol=tcp -N -B -e \
      "SELECT EXISTS(SELECT 1 FROM mysql.user WHERE User='$user' AND Host='%');")
    if [ "$exists" = 1 ]; then
      MYSQL_PWD="$password" mysql -h "$host" -uroot --protocol=tcp <<SQL
ALTER USER '$user'@'%' ACCOUNT LOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$user'@'%';
SQL
      echo "Locked $user on $host and revoked all direct grants"
    fi
  done
}

revoke_readers commerce-db "$COMMERCE_ROOT_PASSWORD" order_shadow_reader payment_shadow_reader fulfillment_shadow
revoke_readers order-db "$ORDER_ROOT_PASSWORD" fulfillment_shadow

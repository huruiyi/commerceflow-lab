#!/bin/sh
set -eu

case "${RUNTIME_DATABASE_USERNAME}" in
  ''|*[!A-Za-z0-9_]* )
    echo "RUNTIME_DATABASE_USERNAME must contain only letters, digits, or underscores" >&2
    exit 1
    ;;
esac

escaped_password=$(printf '%s' "${RUNTIME_DATABASE_PASSWORD}" | sed "s/'/''/g")
root_mysql="mysql -h commerce-db -uroot -p${SOURCE_ROOT_PASSWORD} --protocol=tcp --skip-column-names"

${root_mysql} <<SQL
CREATE USER IF NOT EXISTS '${RUNTIME_DATABASE_USERNAME}'@'%' IDENTIFIED BY '${escaped_password}';
ALTER USER '${RUNTIME_DATABASE_USERNAME}'@'%' IDENTIFIED BY '${escaped_password}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${RUNTIME_DATABASE_USERNAME}'@'%';
SQL

grant_statements=$(${root_mysql} -e "
  SELECT CONCAT(
    'GRANT SELECT, INSERT, UPDATE, DELETE ON \`commerceflow\`.\`',
    table_name,
    '\` TO ''${RUNTIME_DATABASE_USERNAME}''@''%'';'
  )
  FROM information_schema.tables
  WHERE table_schema = 'commerceflow'
    AND table_type = 'BASE TABLE'
    AND table_name NOT IN (
      'flyway_schema_history',
      'product_inventory',
      'inventory_reservations',
      'inventory_movements',
      'inventory_command_inbox',
      'inventory_catalog_event_inbox'
    )
  ORDER BY table_name;
")

if [ -z "${grant_statements}" ]; then
  echo "No commerce runtime tables were found after migration" >&2
  exit 1
fi

printf '%s\n' "${grant_statements}" | ${root_mysql}

for source_table in \
  product_inventory \
  inventory_reservations \
  inventory_movements \
  inventory_command_inbox \
  inventory_catalog_event_inbox
do
  forbidden=$(${root_mysql} -e "
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

echo "Commerce runtime grants exclude all five inventory source tables."

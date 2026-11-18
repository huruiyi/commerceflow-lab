#!/bin/sh
set -eu

case "${1:---check}" in
  --check) complete=0 ;;
  --complete) complete=1 ;;
  *) echo 'Usage: migrate-notification-data.sh [--check|--complete]' >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
  echo 'Expected at most one migration mode' >&2
  exit 2
fi

sql_dir=$(dirname "$0")
work_dir=$(mktemp -d)
snapshot="$work_dir/snapshot.sql"
apply="$work_dir/apply.sql"
trap 'rm -f "$snapshot" "$apply"; rmdir "$work_dir"' EXIT

MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+00:00'" -N -B --raw commerceflow \
  < "$sql_dir/export-snapshot.sql" > "$snapshot"

cat > "$apply" <<SQL
SET @complete=$complete;
CREATE TEMPORARY TABLE migration_snapshot (kind VARCHAR(9) NOT NULL, body JSON NOT NULL);
SQL
cat "$snapshot" "$sql_dir/verify-and-backfill.sql" >> "$apply"
if [ "$complete" -eq 1 ]; then
  printf 'COMMIT;\n' >> "$apply"
else
  printf 'ROLLBACK;\n' >> "$apply"
fi

MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h notification-db -uroot --default-character-set=utf8mb4 \
  --init-command="SET time_zone='+00:00'" -N -B notification < "$apply"

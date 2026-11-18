#!/bin/sh
set -eu

fail() { echo "$*" >&2; exit 1; }
case "${1:-}" in
  --plan) [ "$#" -eq 4 ] || fail 'Expected --plan SOURCE_WATERMARK GENERATION HANDOFF_WATERMARK' ;;
  --apply) [ "$#" -eq 5 ] || fail 'Expected --apply SOURCE_WATERMARK GENERATION HANDOFF_WATERMARK SHA256' ;;
  *) fail 'Choose --plan or --apply after freezing and stopping both execution processes' ;;
esac
mode=$1 source_watermark=$2 generation=$3 handoff_watermark=$4
for value in "$source_watermark" "$generation" "$handoff_watermark"; do
  case "$value" in ''|*[!0-9]*) fail 'Watermarks and generation must be unsigned integers' ;; esac
done

commerce() {
  MYSQL_PWD="$COMMERCE_ROOT_PASSWORD" mysql -h commerce-db -uroot \
    --default-character-set=utf8mb4 --init-command="SET time_zone='+00:00'" -N -B commerceflow "$@"
}
fulfillment() {
  MYSQL_PWD="$FULFILLMENT_ROOT_PASSWORD" mysql -h fulfillment-db -uroot \
    --default-character-set=utf8mb4 --init-command="SET time_zone='+00:00'" -N -B fulfillment "$@"
}

drained="
  NOT EXISTS (SELECT 1 FROM fulfillment_shipment_jobs
    WHERE status IN ('PENDING','REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_merchant_jobs
    WHERE status IN ('PENDING','REVIEW_REQUIRED') OR owner_token IS NOT NULL OR lease_until IS NOT NULL)
"
commerce_ready="
  EXISTS (SELECT 1 FROM fulfillment_write_admission a JOIN fulfillment_shadow_clock c USING(singleton_id)
    WHERE a.singleton_id=1 AND a.frozen=TRUE AND a.write_owner='FULFILLMENT' AND c.last_sequence=$source_watermark)
  AND ($drained)
  AND NOT EXISTS (SELECT 1 FROM logistics_fulfillment_commands
    WHERE shipment_version IS NULL OR owner_token IS NOT NULL OR lease_until IS NOT NULL)
"
fulfillment_ready="
  EXISTS (SELECT 1 FROM fulfillment_runtime_mode m JOIN fulfillment_shadow_watermark w USING(singleton_id)
    WHERE m.singleton_id=1 AND m.mode='EXECUTION' AND w.generation=$generation
      AND w.generation>0 AND w.source_sequence=$handoff_watermark)
  AND ($drained)
  AND NOT EXISTS (SELECT 1 FROM fulfillment_shadow_quarantine)
"
[ "$(commerce -e "SELECT ($commerce_ready);")" = 1 ] || fail 'Commerce ownership, watermark or drain check failed'
[ "$(fulfillment -e "SELECT ($fulfillment_ready);")" = 1 ] || fail 'Fulfillment mode, watermark or drain check failed'

# Keep parents first for inserts and last for deletes.
expected_digest=${5:-}
tables='fulfillment_order_locks fulfillment_command_ids fulfillment_shipment_jobs fulfillment_merchant_jobs fulfillment_shipment_recoveries fulfillment_merchant_recoveries'
reversed=
for table in $tables; do reversed="$table $reversed"; done

work=$(mktemp -d)
trap 'rm -f "$work/dump.sql" "$work/rows.sql" "$work/apply.sql"; rmdir "$work"' EXIT
MYSQL_PWD="$FULFILLMENT_ROOT_PASSWORD" mysqldump -h fulfillment-db -uroot \
  --default-character-set=utf8mb4 --single-transaction --no-create-info --complete-insert --quote-names \
  --order-by-primary --skip-extended-insert --skip-triggers --skip-add-locks --skip-disable-keys \
  --no-tablespaces --set-gtid-purged=OFF --compact fulfillment $tables > "$work/dump.sql"
awk '/^INSERT INTO /' "$work/dump.sql" > "$work/rows.sql"
digest=$(sha256sum "$work/rows.sql" | cut -d ' ' -f 1)
echo "Six-table return snapshot SHA256=$digest"
[ "$mode" = --apply ] || exit 0
[ "$digest" = "$expected_digest" ] || fail 'Fulfillment snapshot differs from the reviewed data'
[ "$(fulfillment -e "SELECT ($fulfillment_ready);")" = 1 ] || fail 'Fulfillment state changed since snapshot'

{
  echo 'CREATE TEMPORARY TABLE return_guard (verified INT PRIMARY KEY);'
  echo 'INSERT INTO return_guard VALUES (0);'
  echo "INSERT INTO return_guard VALUES (COALESCE(GET_LOCK('commerceflow.fulfillment-source-owner',30),0));"
  for table in $tables; do echo "CREATE TEMPORARY TABLE restore_$table LIKE $table;"; done
  awk '
    !/^INSERT INTO `fulfillment_[a-z_]+` \(/ { exit 1 }
    { sub(/^INSERT INTO `fulfillment_/, "INSERT INTO `restore_fulfillment_"); print }
  ' "$work/rows.sql"
  echo 'START TRANSACTION;'
  echo 'SELECT write_owner INTO @owner FROM fulfillment_write_admission WHERE singleton_id=1 FOR UPDATE;'
  echo 'SELECT last_sequence INTO @watermark FROM fulfillment_shadow_clock WHERE singleton_id=1 FOR UPDATE;'
  echo "INSERT INTO return_guard SELECT IF(($commerce_ready),2,0);"
  for table in $reversed; do echo "DELETE FROM $table;"; done
  for table in $tables; do
    columns=$(commerce -e "
      SET SESSION group_concat_max_len=16384;
      SELECT GROUP_CONCAT(CONCAT('BINARY s.\`',COLUMN_NAME,'\` <=> BINARY r.\`',COLUMN_NAME,'\`')
        ORDER BY ORDINAL_POSITION SEPARATOR ' AND ')
      FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='$table';")
    [ -n "$columns" ] && [ "$columns" != NULL ] || fail "Missing table metadata for $table"
    echo "INSERT INTO $table SELECT * FROM restore_$table;"
    echo "SELECT COUNT(*) INTO @expected_rows FROM restore_$table;"
    echo "SELECT COUNT(*) INTO @returned_rows FROM $table;"
    echo "INSERT INTO return_guard
      SELECT IF(@returned_rows=@expected_rows
        AND NOT EXISTS (SELECT 1 FROM $table s WHERE NOT EXISTS
          (SELECT 1 FROM restore_$table r WHERE $columns)), 3, 0);"
    echo 'DELETE FROM return_guard WHERE verified=3;'
    echo "SELECT '$table',COUNT(*) FROM $table;"
  done
  echo 'SELECT last_sequence AS source_watermark_after_return FROM fulfillment_shadow_clock WHERE singleton_id=1;'
  echo 'COMMIT;'
  echo "DO RELEASE_LOCK('commerceflow.fulfillment-source-owner');"
} > "$work/apply.sql"

commerce < "$work/apply.sql" || fail 'Six-table return rolled back; Commerce remains frozen'

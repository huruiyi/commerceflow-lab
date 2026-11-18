#!/bin/sh
set -eu

cutover_key=catalog-v29-single-owner
source_version=29

source_query() {
  MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot --default-character-set=utf8mb4 \
    --init-command="SET time_zone='+00:00'" -N -B commerceflow "$@"
}

target_query() {
  MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h catalog-db -uroot --default-character-set=utf8mb4 \
    --init-command="SET time_zone='+00:00'" -N -B catalog "$@"
}

empty_target="
  NOT EXISTS (SELECT 1 FROM catalog_cutover_state WHERE cutover_key<>'$cutover_key' OR status='COMPLETE')
  AND NOT EXISTS (SELECT 1 FROM products)
  AND NOT EXISTS (SELECT 1 FROM product_translations)
  AND NOT EXISTS (SELECT 1 FROM catalog_event_outbox)
"
state="$(target_query -e "
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM catalog_cutover_state WHERE cutover_key='$cutover_key' AND status='COMPLETE'
  ) THEN 'COMPLETE' WHEN ($empty_target) THEN 'EMPTY' ELSE 'OCCUPIED' END;
")"
case "$state" in
  COMPLETE) echo 'Catalog cutover already COMPLETE; legacy import skipped'; exit 0 ;;
  EMPTY) ;;
  *) echo 'Catalog target contains business data or an initialization marker; legacy import refused' >&2; exit 1 ;;
esac

actual_version="$(source_query -e 'SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1')"
if [ "$actual_version" -lt "$source_version" ]; then
  echo "Commerce schema version $actual_version is below V$source_version" >&2
  exit 1
fi

evidence_query="$(cat <<'SQL'
SELECT
  (SELECT COUNT(*) FROM products) AS products_count,
  (SELECT COUNT(*) FROM product_translations) AS translations_count,
  (SELECT COUNT(*) FROM catalog_event_outbox) AS outbox_count,
  (SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,name,brief,description,price,
    image_url,thumbnail_url,merchant_id,lifecycle_status,business_version,
    DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0)
    FROM products) AS products_digest,
  (SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,locale,name,brief,description))
    AS UNSIGNED)),0) FROM product_translations) AS translations_digest,
  (SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,event_type,aggregate_id,
    schema_version,business_version,DATE_FORMAT(occurred_at,'%Y-%m-%d %H:%i:%s.%f'),
    correlation_id,causation_id,payload,status,attempts,
    DATE_FORMAT(next_attempt_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(last_error,''),
    DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),
    COALESCE(DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0)
    FROM catalog_event_outbox) AS outbox_digest
SQL
)"
evidence="$(source_query -e "$evidence_query")"
set -- $evidence
if [ "$#" -ne 6 ]; then
  echo 'Could not determine source counts and fingerprints' >&2
  exit 1
fi
for value do
  case "$value" in ''|*[!0-9]*) echo 'Invalid source evidence' >&2; exit 1 ;; esac
done

work_dir="$(mktemp -d)"
export_file="$work_dir/export.sql"
import_file="$work_dir/import.sql"
trap 'rm -f "$export_file" "$import_file"; rmdir "$work_dir"' EXIT
MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysqldump -h commerce-db -uroot \
  --default-character-set=utf8mb4 --single-transaction --no-create-info --complete-insert \
  --skip-triggers --skip-add-locks --skip-disable-keys --no-tablespaces --set-gtid-purged=OFF --compact \
  commerceflow products product_translations catalog_event_outbox > "$export_file"

# Keep the guard, copied rows and cutover marker on one target connection.
cat > "$import_file" <<SQL
CREATE TEMPORARY TABLE import_guard (verified INT PRIMARY KEY);
INSERT INTO import_guard VALUES (0);
INSERT INTO import_guard VALUES (COALESCE(GET_LOCK('commerceflow.catalog.bootstrap',30),0));
START TRANSACTION;
-- Lock empty table ranges as well, so a business write cannot slip in after the check.
SELECT COUNT(*) INTO @products FROM products FOR UPDATE;
SELECT COUNT(*) INTO @translations FROM product_translations FOR UPDATE;
SELECT COUNT(*) INTO @events FROM catalog_event_outbox FOR UPDATE;
INSERT INTO import_guard SELECT IF(($empty_target)
  AND @products=0 AND @translations=0 AND @events=0,2,0);
SQL
cat "$export_file" >> "$import_file"
cat >> "$import_file" <<SQL
INSERT INTO import_guard
SELECT IF(CONCAT_WS(',',products_count,translations_count,outbox_count,products_digest,
  translations_digest,outbox_digest)='$1,$2,$3,$4,$5,$6',3,0)
FROM ($evidence_query) AS imported;
INSERT INTO catalog_cutover_state(cutover_key,status,source_version,product_count,translation_count,outbox_count,
  product_fingerprint,translation_fingerprint,outbox_fingerprint,completed_at)
VALUES('$cutover_key','COMPLETE',$source_version,$1,$2,$3,$4,$5,$6,CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE status='COMPLETE',source_version=$source_version,product_count=$1,
  translation_count=$2,outbox_count=$3,product_fingerprint=$4,translation_fingerprint=$5,
  outbox_fingerprint=$6,completed_at=CURRENT_TIMESTAMP(6);
COMMIT;
DO RELEASE_LOCK('commerceflow.catalog.bootstrap');
SQL

if ! target_query < "$import_file"; then
  echo 'Catalog legacy import refused or rolled back; verify target state and source evidence' >&2
  exit 1
fi
echo "Catalog cutover COMPLETE: products=$1, translations=$2, outbox=$3"

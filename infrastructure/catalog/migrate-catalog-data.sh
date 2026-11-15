#!/bin/sh
set -eu

CUTOVER_KEY="catalog-v29-single-owner"
SOURCE_SCHEMA="commerceflow"
TARGET_SCHEMA="catalog"
SOURCE_VERSION="29"
EXPORT_FILE="/tmp/catalog-cutover.sql"

source_query() {
  MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysql -h commerce-db -uroot -N -B "$SOURCE_SCHEMA" -e "$1"
}

target_query() {
  MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h catalog-db -uroot -N -B "$TARGET_SCHEMA" -e "$1"
}

status="$(target_query "SELECT COALESCE(MAX(status),'') FROM catalog_cutover_state WHERE cutover_key='${CUTOVER_KEY}'")"
if [ "$status" = "COMPLETE" ]; then
  echo "catalog cutover already COMPLETE; stale commerce source will not be copied again"
  exit 0
fi

attempt=0
actual_version=0
while [ "$attempt" -lt 60 ]; do
  actual_version="$(source_query "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1" 2>/dev/null || echo 0)"
  if [ "$actual_version" -ge "$SOURCE_VERSION" ]; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done
if [ "$actual_version" -lt "$SOURCE_VERSION" ]; then
  echo "commerce schema version ${actual_version} is below required V${SOURCE_VERSION}" >&2
  exit 1
fi

target_products="$(target_query "SELECT COUNT(*) FROM products")"
target_translations="$(target_query "SELECT COUNT(*) FROM product_translations")"
target_outbox="$(target_query "SELECT COUNT(*) FROM catalog_event_outbox")"
if [ "$target_products" -ne 0 ] || [ "$target_translations" -ne 0 ] || [ "$target_outbox" -ne 0 ]; then
  target_query "INSERT INTO catalog_cutover_state(cutover_key,status,source_version,product_count,translation_count,product_fingerprint,translation_fingerprint) VALUES ('${CUTOVER_KEY}','FAILED',${SOURCE_VERSION},0,0,0,0) ON DUPLICATE KEY UPDATE status='FAILED'"
  echo "catalog target is non-empty without a COMPLETE cutover marker" >&2
  exit 1
fi

source_products="$(source_query "SELECT COUNT(*) FROM products")"
source_translations="$(source_query "SELECT COUNT(*) FROM product_translations")"
source_outbox="$(source_query "SELECT COUNT(*) FROM catalog_event_outbox")"
source_product_fingerprint="$(source_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,name,brief,description,price,image_url,thumbnail_url,merchant_id,lifecycle_status,business_version,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0) FROM products")"
source_translation_fingerprint="$(source_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,locale,name,brief,description)) AS UNSIGNED)),0) FROM product_translations")"
source_outbox_fingerprint="$(source_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,event_type,aggregate_id,schema_version,business_version,DATE_FORMAT(occurred_at,'%Y-%m-%d %H:%i:%s.%f'),correlation_id,causation_id,payload,status,attempts,DATE_FORMAT(next_attempt_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(last_error,''),DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0) FROM catalog_event_outbox")"

target_query "INSERT INTO catalog_cutover_state(cutover_key,status,source_version,product_count,translation_count,outbox_count,product_fingerprint,translation_fingerprint,outbox_fingerprint) VALUES ('${CUTOVER_KEY}','RUNNING',${SOURCE_VERSION},${source_products},${source_translations},${source_outbox},${source_product_fingerprint},${source_translation_fingerprint},${source_outbox_fingerprint}) ON DUPLICATE KEY UPDATE status='RUNNING',source_version=VALUES(source_version),product_count=VALUES(product_count),translation_count=VALUES(translation_count),outbox_count=VALUES(outbox_count),product_fingerprint=VALUES(product_fingerprint),translation_fingerprint=VALUES(translation_fingerprint),outbox_fingerprint=VALUES(outbox_fingerprint),completed_at=NULL"

MYSQL_PWD="$SOURCE_ROOT_PASSWORD" mysqldump -h commerce-db -uroot \
  --default-character-set=utf8mb4 \
  --no-create-info --skip-triggers --skip-add-locks --skip-disable-keys --compact \
  "$SOURCE_SCHEMA" products product_translations catalog_event_outbox > "$EXPORT_FILE"
MYSQL_PWD="$TARGET_ROOT_PASSWORD" mysql -h catalog-db -uroot \
  --default-character-set=utf8mb4 "$TARGET_SCHEMA" < "$EXPORT_FILE"

target_products="$(target_query "SELECT COUNT(*) FROM products")"
target_translations="$(target_query "SELECT COUNT(*) FROM product_translations")"
target_outbox="$(target_query "SELECT COUNT(*) FROM catalog_event_outbox")"
target_product_fingerprint="$(target_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,name,brief,description,price,image_url,thumbnail_url,merchant_id,lifecycle_status,business_version,DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'))) AS UNSIGNED)),0) FROM products")"
target_translation_fingerprint="$(target_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',product_id,locale,name,brief,description)) AS UNSIGNED)),0) FROM product_translations")"
target_outbox_fingerprint="$(target_query "SELECT COALESCE(BIT_XOR(CAST(CRC32(CONCAT_WS('|',id,event_id,event_type,aggregate_id,schema_version,business_version,DATE_FORMAT(occurred_at,'%Y-%m-%d %H:%i:%s.%f'),correlation_id,causation_id,payload,status,attempts,DATE_FORMAT(next_attempt_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(last_error,''),DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),COALESCE(DATE_FORMAT(published_at,'%Y-%m-%d %H:%i:%s.%f'),''))) AS UNSIGNED)),0) FROM catalog_event_outbox")"

if [ "$source_products" != "$target_products" ] \
  || [ "$source_translations" != "$target_translations" ] \
  || [ "$source_outbox" != "$target_outbox" ] \
  || [ "$source_product_fingerprint" != "$target_product_fingerprint" ] \
  || [ "$source_translation_fingerprint" != "$target_translation_fingerprint" ] \
  || [ "$source_outbox_fingerprint" != "$target_outbox_fingerprint" ]; then
  target_query "UPDATE catalog_cutover_state SET status='FAILED' WHERE cutover_key='${CUTOVER_KEY}'"
  echo "catalog cutover count or fingerprint mismatch" >&2
  exit 1
fi

target_query "UPDATE catalog_cutover_state SET status='COMPLETE',completed_at=CURRENT_TIMESTAMP(6) WHERE cutover_key='${CUTOVER_KEY}'"
echo "catalog cutover COMPLETE: products=${target_products}, translations=${target_translations}, outbox=${target_outbox}"

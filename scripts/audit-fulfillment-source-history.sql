START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'FULFILLMENT_TABLES', COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('fulfillment_shipment_jobs', 'fulfillment_shipment_recoveries',
    'fulfillment_merchant_jobs', 'fulfillment_merchant_recoveries', 'fulfillment_order_locks', 'fulfillment_command_ids');

WITH owned_tables AS (
  SELECT table_name
  FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND table_name IN ('fulfillment_shipment_jobs', 'fulfillment_shipment_recoveries',
      'fulfillment_merchant_jobs', 'fulfillment_merchant_recoveries', 'fulfillment_order_locks', 'fulfillment_command_ids')
)
SELECT 'FULFILLMENT_CROSS_BOUNDARY_FKS', COUNT(DISTINCT usage_row.constraint_schema,
  usage_row.table_name, usage_row.constraint_name)
FROM information_schema.key_column_usage AS usage_row
LEFT JOIN owned_tables AS source_table
  ON usage_row.table_schema = DATABASE() AND usage_row.table_name = source_table.table_name
LEFT JOIN owned_tables AS target_table
  ON usage_row.referenced_table_schema = DATABASE() AND usage_row.referenced_table_name = target_table.table_name
WHERE usage_row.referenced_table_name IS NOT NULL
  AND ((source_table.table_name IS NULL) <> (target_table.table_name IS NULL));

WITH jobs AS (
  SELECT order_id, status, owner_token, lease_until
  FROM fulfillment_shipment_jobs
  UNION ALL
  SELECT order_id, status, owner_token, lease_until
  FROM fulfillment_merchant_jobs
)
SELECT 'FULFILLMENT_JOBS', COUNT(*) FROM jobs
UNION ALL
SELECT 'FULFILLMENT_PENDING', COUNT(*) FROM jobs WHERE status = 'PENDING'
UNION ALL
SELECT 'FULFILLMENT_REVIEW_REQUIRED', COUNT(*) FROM jobs WHERE status = 'REVIEW_REQUIRED'
UNION ALL
SELECT 'FULFILLMENT_ACTIVE_LEASES', COUNT(*) FROM jobs WHERE lease_until > CURRENT_TIMESTAMP(6)
UNION ALL
SELECT 'FULFILLMENT_EXPIRED_LEASES', COUNT(*) FROM jobs
WHERE status = 'PENDING' AND lease_until <= CURRENT_TIMESTAMP(6)
UNION ALL
SELECT 'FULFILLMENT_INVALID_LEASES', COUNT(*) FROM jobs
WHERE ((owner_token IS NULL) <> (lease_until IS NULL))
  OR (status <> 'PENDING' AND owner_token IS NOT NULL)
UNION ALL
SELECT 'FULFILLMENT_ORDERS_WITH_MULTIPLE_OPEN_JOBS', COUNT(*) FROM (
  SELECT order_id FROM jobs WHERE status IN ('PENDING', 'REVIEW_REQUIRED')
  GROUP BY order_id HAVING COUNT(*) > 1
) AS overlapping;

SELECT 'FULFILLMENT_UNRESOLVED_RECOVERY_REFERENCES', COUNT(*) FROM (
  SELECT recovery.id
  FROM fulfillment_shipment_recoveries AS recovery
  LEFT JOIN fulfillment_shipment_jobs AS job
    ON job.order_id = recovery.order_id AND job.command_id = recovery.command_id
  WHERE job.command_id IS NULL
  UNION ALL
  SELECT recovery.id
  FROM fulfillment_merchant_recoveries AS recovery
  LEFT JOIN fulfillment_merchant_jobs AS job ON job.command_id = recovery.command_id
  WHERE job.command_id IS NULL
) AS unresolved;

WITH commands AS (
  SELECT command_id, order_id FROM fulfillment_shipment_jobs
  UNION ALL
  SELECT command_id, order_id FROM fulfillment_merchant_jobs
)
SELECT 'FULFILLMENT_COMMAND_REGISTRY_MISMATCHES', COUNT(*) FROM (
  SELECT job.command_id FROM commands AS job
  LEFT JOIN fulfillment_command_ids AS registration
    ON job.command_id = registration.command_id AND job.order_id = registration.order_id
  WHERE registration.command_id IS NULL
  UNION ALL
  SELECT registration.command_id FROM fulfillment_command_ids AS registration
  LEFT JOIN commands AS job
    ON job.command_id = registration.command_id AND job.order_id = registration.order_id
  WHERE job.command_id IS NULL
  UNION ALL
  SELECT command_id FROM commands GROUP BY command_id HAVING COUNT(*) > 1
) AS mismatches;

COMMIT;

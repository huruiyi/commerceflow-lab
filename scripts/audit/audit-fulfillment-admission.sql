START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'FULFILLMENT_FROZEN_SOURCE', COUNT(*)
FROM fulfillment_write_admission
WHERE singleton_id=1 AND write_owner='FULFILLMENT' AND frozen=TRUE;

SELECT 'FULFILLMENT_RUNTIME_OLD_TABLE_GRANTS', COUNT(*)
FROM information_schema.table_privileges
WHERE table_schema=DATABASE() AND grantee=CONCAT(QUOTE(@commerce_runtime_user), '@', QUOTE('%'))
  AND table_name IN ('fulfillment_order_locks','fulfillment_command_ids','fulfillment_shipment_jobs',
    'fulfillment_merchant_jobs','fulfillment_shipment_recoveries','fulfillment_merchant_recoveries',
    'fulfillment_shadow_clock','fulfillment_shadow_changes');

SELECT 'LOGISTICS_TABLES', COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('shipment_records', 'shipment_events', 'shipment_notifications', 'logistics_fulfillment_commands');

SELECT 'LOGISTICS_COMMANDS', COUNT(*) FROM logistics_fulfillment_commands;

SELECT 'LOGISTICS_COMMANDS_PENDING', COUNT(*) FROM logistics_fulfillment_commands WHERE shipment_version IS NULL;

SELECT 'LOGISTICS_COMMAND_STATE_MISMATCHES', COUNT(*)
FROM logistics_fulfillment_commands
WHERE ((owner_token IS NULL) <> (lease_until IS NULL))
  OR ((platform_shipment_no IS NULL) <> (shipment_version IS NULL))
  OR (shipment_version IS NOT NULL AND owner_token IS NOT NULL);

SELECT 'LOGISTICS_RECEIPT_MISMATCHES', COUNT(*)
FROM logistics_fulfillment_commands AS command_row
WHERE command_row.shipment_version IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM shipment_records AS projection
    WHERE projection.order_id = command_row.order_id
      AND projection.platform_shipment_no = command_row.platform_shipment_no
      AND (command_row.package_id IS NULL OR projection.package_id = command_row.package_id)
      AND (projection.shipment_version > command_row.shipment_version
        OR (projection.shipment_version = command_row.shipment_version
          AND projection.carrier <=> command_row.carrier
          AND projection.tracking_number <=> command_row.tracking_number))
  );

SELECT 'LOGISTICS_CROSS_BOUNDARY_FKS', COUNT(*)
FROM information_schema.key_column_usage
WHERE referenced_table_name IS NOT NULL
  AND ((table_schema = DATABASE()
    AND table_name IN ('shipment_records', 'shipment_events', 'shipment_notifications', 'logistics_fulfillment_commands')
    AND (referenced_table_schema <> DATABASE()
      OR referenced_table_name NOT IN
        ('shipment_records', 'shipment_events', 'shipment_notifications', 'logistics_fulfillment_commands')))
    OR (referenced_table_schema = DATABASE()
      AND referenced_table_name IN
        ('shipment_records', 'shipment_events', 'shipment_notifications', 'logistics_fulfillment_commands')
      AND (table_schema <> DATABASE()
        OR table_name NOT IN
        ('shipment_records', 'shipment_events', 'shipment_notifications', 'logistics_fulfillment_commands'))))
  -- The storefront return shipment keeps a reference to its case in the same Commerce schema.
  AND NOT (table_schema = DATABASE() AND referenced_table_schema = DATABASE()
    AND constraint_name = 'fk_storefront_return_case'
    AND table_name = 'shipment_records' AND column_name = 'after_sale_id'
    AND referenced_table_name = 'line_after_sales' AND referenced_column_name = 'id');

SELECT 'SHIPMENT_RECORDS', COUNT(*) FROM shipment_records;

SELECT 'UNATTACHED_SHIPMENTS', COUNT(*)
FROM shipment_records
WHERE platform_shipment_no IS NULL;

SELECT 'UNRESOLVED_NOTIFICATION_REFERENCES', COUNT(*)
FROM shipment_notifications AS notification
LEFT JOIN shipment_records AS shipment ON shipment.id = notification.shipment_id
WHERE notification.shipment_id IS NOT NULL AND shipment.id IS NULL;

COMMIT;

SELECT 'LOGISTICS_TABLES', COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('shipment_records', 'shipment_events', 'shipment_notifications');

SELECT 'LOGISTICS_CROSS_BOUNDARY_FKS', COUNT(*)
FROM information_schema.key_column_usage
WHERE referenced_table_name IS NOT NULL
  AND ((table_schema = DATABASE()
    AND table_name IN ('shipment_records', 'shipment_events', 'shipment_notifications')
    AND (referenced_table_schema <> DATABASE()
      OR referenced_table_name NOT IN ('shipment_records', 'shipment_events', 'shipment_notifications')))
    OR (referenced_table_schema = DATABASE()
      AND referenced_table_name IN ('shipment_records', 'shipment_events', 'shipment_notifications')
      AND (table_schema <> DATABASE()
        OR table_name NOT IN ('shipment_records', 'shipment_events', 'shipment_notifications'))));

SELECT 'SHIPMENT_RECORDS', COUNT(*) FROM shipment_records;

SELECT 'UNATTACHED_SHIPMENTS', COUNT(*)
FROM shipment_records
WHERE platform_shipment_no IS NULL;

SELECT 'UNRESOLVED_NOTIFICATION_REFERENCES', COUNT(*)
FROM shipment_notifications AS notification
LEFT JOIN shipment_records AS shipment ON shipment.id = notification.shipment_id
WHERE notification.shipment_id IS NOT NULL AND shipment.id IS NULL;

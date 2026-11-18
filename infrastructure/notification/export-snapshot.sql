SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT CONCAT('INSERT INTO migration_snapshot VALUES (''history'', CONVERT(0x',
  HEX(JSON_OBJECT('id',id,'eventId',event_id,'customerId',customer_id,'orderId',order_id,
    'notificationType',notification_type,'title',title,'message',message,'status',status,
    'readAt',CAST(read_at AS CHAR),'createdAt',CAST(created_at AS CHAR))),
  ' USING utf8mb4));')
FROM customer_notifications ORDER BY id;

SELECT CONCAT('INSERT INTO migration_snapshot VALUES (''published'', CONVERT(0x',
  HEX(JSON_OBJECT('id',id,'eventId',event_id,'command',payload)), ' USING utf8mb4));')
FROM notification_delivery_outbox WHERE status='PUBLISHED' ORDER BY id;

COMMIT;

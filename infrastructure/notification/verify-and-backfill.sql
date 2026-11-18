CREATE TEMPORARY TABLE migration_history LIKE customer_notifications;
INSERT INTO migration_history
SELECT history.*
FROM migration_snapshot, JSON_TABLE(body,'$' COLUMNS (
  id BIGINT PATH '$.id' ERROR ON EMPTY,
  event_id VARCHAR(100) PATH '$.eventId' ERROR ON EMPTY,
  customer_id BIGINT PATH '$.customerId' ERROR ON EMPTY,
  order_id BIGINT PATH '$.orderId',
  notification_type VARCHAR(50) PATH '$.notificationType' ERROR ON EMPTY,
  title VARCHAR(160) PATH '$.title' ERROR ON EMPTY,
  message VARCHAR(500) PATH '$.message' ERROR ON EMPTY,
  status VARCHAR(20) PATH '$.status' ERROR ON EMPTY,
  read_at DATETIME(6) PATH '$.readAt',
  created_at DATETIME(6) PATH '$.createdAt' ERROR ON EMPTY
)) AS history
WHERE kind='history';

CREATE TEMPORARY TABLE migration_published (
  source_id BIGINT NOT NULL PRIMARY KEY,
  event_id VARCHAR(100) NOT NULL UNIQUE,
  command JSON NOT NULL
);
INSERT INTO migration_published
SELECT published.*
FROM migration_snapshot, JSON_TABLE(body,'$' COLUMNS (
  source_id BIGINT PATH '$.id' ERROR ON EMPTY,
  event_id VARCHAR(100) PATH '$.eventId' ERROR ON EMPTY,
  command JSON PATH '$.command' ERROR ON EMPTY
)) AS published
WHERE kind='published';

CREATE TEMPORARY TABLE migration_guard (verified INT PRIMARY KEY);
INSERT INTO migration_guard VALUES (0);
INSERT INTO migration_guard VALUES (COALESCE(GET_LOCK('commerceflow.notification.backfill',30),0));

SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SELECT backfill_completed,source_watermark INTO @completed,@previous_watermark
FROM notification_storage_state WHERE id=1 FOR UPDATE;
SELECT COUNT(*),COALESCE(MAX(id),0) INTO @history_count,@watermark FROM migration_history;
INSERT INTO migration_guard VALUES (IF(@completed=0 OR (@completed=1 AND @previous_watermark=@watermark),2,0));

-- The target owns read state. Restore a legacy read receipt only while the target is unread.
INSERT INTO customer_notifications(event_id,customer_id,order_id,notification_type,title,message,status,
                                   read_at,created_at)
SELECT h.event_id,h.customer_id,h.order_id,h.notification_type,h.title,h.message,h.status,h.read_at,h.created_at
FROM migration_history h
LEFT JOIN customer_notifications n ON n.event_id=h.event_id
WHERE n.id IS NULL AND @completed=0
ORDER BY h.id;
SET @inserted=ROW_COUNT();

UPDATE customer_notifications n JOIN migration_history h ON h.event_id=n.event_id
SET n.status='READ',n.read_at=h.read_at
WHERE n.status='UNREAD' AND h.status='READ' AND h.read_at IS NOT NULL AND @completed=0;
SET @read_receipts_restored=ROW_COUNT();

SELECT
  COUNT(CASE WHEN n.id IS NULL THEN 1 END),
  COUNT(CASE WHEN h.status NOT IN ('READ','UNREAD') OR (h.status='READ')<>(h.read_at IS NOT NULL)
    OR (n.id IS NOT NULL AND (
      BINARY n.event_id<>BINARY h.event_id OR n.customer_id<>h.customer_id OR NOT(n.order_id<=>h.order_id)
      OR BINARY n.notification_type<>BINARY h.notification_type OR BINARY n.title<>BINARY h.title
      OR BINARY n.message<>BINARY h.message OR n.status NOT IN ('READ','UNREAD')
      OR (n.status='READ')<>(n.read_at IS NOT NULL) OR (h.status='READ' AND n.status<>'READ')
    )) THEN 1 END)
INTO @history_missing,@history_mismatch
FROM migration_history h LEFT JOIN customer_notifications n ON n.event_id=h.event_id;

SELECT COUNT(*),
  COUNT(CASE WHEN n.id IS NULL OR i.id IS NULL THEN 1 END),
  COUNT(CASE WHEN NOT(BINARY p.event_id<=>BINARY (p.command->>'$.eventId'))
    OR (n.id IS NOT NULL AND (
      BINARY n.event_id<>BINARY p.event_id
      OR NOT(CAST(n.customer_id AS JSON)<=>JSON_EXTRACT(p.command,'$.customerId'))
      OR NOT(CAST(n.order_id AS JSON)<=>NULLIF(JSON_EXTRACT(p.command,'$.orderId'),CAST('null' AS JSON)))
      OR NOT(BINARY n.notification_type<=>BINARY (p.command->>'$.notificationType'))
      OR NOT(BINARY n.title<=>BINARY (p.command->>'$.title'))
      OR NOT(BINARY n.message<=>BINARY (p.command->>'$.message'))
    )) OR (i.id IS NOT NULL AND (
      BINARY i.event_id<>BINARY p.event_id OR NOT(BINARY i.event_type<=>BINARY (p.command->>'$.eventType'))
      OR NOT(CAST(i.schema_version AS JSON)<=>JSON_EXTRACT(p.command,'$.schemaVersion'))
      OR NOT(BINARY i.correlation_id<=>BINARY (p.command->>'$.correlationId'))
      OR NOT(BINARY i.causation_id<=>BINARY (p.command->>'$.causationId'))
    )) THEN 1 END)
INTO @published_count,@published_missing,@published_mismatch
FROM migration_published p
LEFT JOIN customer_notifications n ON n.event_id=p.event_id
LEFT JOIN notification_inbox i ON i.event_id=p.event_id AND i.consumer_name='commerceflow-independent-notification-v1';

SELECT COUNT(*) INTO @orphans FROM customer_notifications n
LEFT JOIN migration_history h ON h.event_id=n.event_id
LEFT JOIN migration_published p ON p.event_id=n.event_id
WHERE h.id IS NULL AND p.source_id IS NULL;

SELECT 'SUMMARY',@history_count,@watermark,@published_count,@inserted,@read_receipts_restored,
  @history_missing,@history_mismatch,@published_missing,@published_mismatch,@orphans;
INSERT INTO migration_guard VALUES (IF(
  @history_missing+@history_mismatch+@published_missing+@published_mismatch+@orphans=0,3,0));

INSERT INTO notification_storage_audits(matched_count,missing_count,orphan_count,mismatch_count,
                                        backfill_completed,passed)
SELECT (SELECT COUNT(*) FROM (
    SELECT event_id FROM migration_history UNION DISTINCT SELECT event_id FROM migration_published
  ) AS checked_events),0,0,0,TRUE,TRUE
WHERE @complete=1 AND @completed=0;
UPDATE notification_storage_state
SET source_watermark=@watermark,backfill_completed=TRUE,completed_at=CURRENT_TIMESTAMP(6)
WHERE id=1 AND @complete=1 AND @completed=0;

SELECT 'PUBLISHED',source_id,event_id FROM migration_published ORDER BY source_id;

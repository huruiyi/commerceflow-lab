package vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.row.NotificationStorageRecord;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface StoreMapper {

  List<CustomerNotification> list(Long customerId);

  int unreadCount(Long customerId);

  int markRead(@Param("customerId") Long customerId, @Param("id") Long id);

  int markReadByEventId(@Param("customerId") Long customerId,
                        @Param("eventId") String eventId);

  int markAllRead(Long customerId);

  String eventIdById(@Param("customerId") Long customerId, @Param("id") Long id);

  int insertInbox(@Param("consumerName") String consumerName,
                  @Param("eventId") String eventId,
                  @Param("eventType") String eventType,
                  @Param("schemaVersion") int schemaVersion,
                  @Param("correlationId") String correlationId,
                  @Param("causationId") String causationId,
                  @Param("traceparent") String traceparent);

  int insertNotification(@Param("eventId") String eventId,
                         @Param("customerId") Long customerId,
                         @Param("orderId") Long orderId,
                         @Param("type") String type,
                         @Param("title") String title,
                         @Param("message") String message);

  int insertBackfill(NotificationStorageRecord notification);

  int synchronizeBackfillState(NotificationStorageRecord notification);

  long backfillWatermark();

  boolean backfillCompleted();

  int advanceBackfillWatermark(@Param("watermark") long watermark,
                               @Param("completed") boolean completed);

  NotificationStorageRecord findStorageRecordByEventId(String eventId);

  List<NotificationStorageRecord> storageRecordsAfter(@Param("afterId") long afterId,
                                                      @Param("limit") int limit);

  int insertStorageAudit(@Param("matched") int matched,
                         @Param("missing") int missing,
                         @Param("orphan") int orphan,
                         @Param("mismatch") int mismatch,
                         @Param("backfillCompleted") boolean backfillCompleted,
                         @Param("passed") boolean passed);

  int recentPassingStorageChecks(@Param("limit") int limit);

  int archiveOrphan(String eventId);

  int deleteOrphan(String eventId);
}

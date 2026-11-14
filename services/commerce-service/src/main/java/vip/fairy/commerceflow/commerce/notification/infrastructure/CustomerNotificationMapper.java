package vip.fairy.commerceflow.commerce.notification.infrastructure;

import java.util.List;
import java.time.Instant;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.domain.NotificationOutboxEntry;
import org.apache.ibatis.annotations.Param;

public interface CustomerNotificationMapper {

  List<CustomerNotification> list(Long customerId);


  int unreadCount(Long customerId);


  int markRead(@Param("customerId") Long customerId, @Param("id") Long id);


  int markAllRead(Long customerId);


  int insertInbox(@Param("consumerName") String consumerName, @Param("eventId") String eventId,
                  @Param("eventType") String eventType, @Param("schemaVersion") int schemaVersion,
                  @Param("correlationId") String correlationId,
                  @Param("causationId") String causationId,
                  @Param("traceparent") String traceparent);


  int insertNotification(@Param("eventId") String eventId, @Param("customerId") Long customerId,
                         @Param("orderId") Long orderId, @Param("type") String type,
                         @Param("title") String title, @Param("message") String message);


  int insertOutbox(@Param("eventId") String eventId, @Param("topic") String topic,
                   @Param("payload") String payload);


  List<NotificationOutboxEntry> pendingOutbox(@Param("limit") int limit);


  int markOutboxPublished(Long id);


  int markOutboxRetry(@Param("id") Long id, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error);


  int markOutboxFallback(@Param("id") Long id, @Param("error") String error);


  int markOutboxDead(@Param("id") Long id, @Param("error") String error);


  int replayDeadOutbox(@Param("eventId") String eventId);
}

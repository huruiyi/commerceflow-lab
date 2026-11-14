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


  Long runningProjectionWindowId();


  int startProjectionWindow();


  int markProjectionWindowReady(Long windowId);


  int markProjectionWindowRunning(Long windowId);


  int stopProjectionWindow(Long windowId);


  Long projectionWindowStartNotificationId(Long windowId);


  String projectionWindowStatus(Long windowId);


  int insertProjection(@Param("windowId") Long windowId, @Param("eventId") String eventId,
                       @Param("customerId") Long customerId,
                       @Param("orderId") Long orderId, @Param("type") String type,
                       @Param("title") String title, @Param("message") String message,
                       @Param("correlationId") String correlationId,
                       @Param("causationId") String causationId,
                       @Param("traceparent") String traceparent);


  int matchedProjectionCount(@Param("windowId") Long windowId,
                             @Param("startNotificationId") Long startNotificationId);


  int missingShadowProjectionCount(@Param("windowId") Long windowId,
                                   @Param("startNotificationId") Long startNotificationId);


  int orphanShadowProjectionCount(@Param("windowId") Long windowId);


  int mismatchedProjectionCount(@Param("windowId") Long windowId,
                                @Param("startNotificationId") Long startNotificationId);


  int blockingOutboxCount(@Param("windowId") Long windowId);


  int insertProjectionAudit(@Param("windowId") Long windowId,
                            @Param("matched") int matched,
                            @Param("missingShadow") int missingShadow,
                            @Param("orphanShadow") int orphanShadow,
                            @Param("mismatch") int mismatch,
                            @Param("blockingOutbox") int blockingOutbox,
                            @Param("passed") boolean passed);


  int recentPassingProjectionChecks(@Param("windowId") Long windowId,
                                    @Param("limit") int limit);
}

package vip.fairy.commerceflow.notification.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationStoreMapper {

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

  int notificationCount();

  int inboxCount(String consumerName);
}

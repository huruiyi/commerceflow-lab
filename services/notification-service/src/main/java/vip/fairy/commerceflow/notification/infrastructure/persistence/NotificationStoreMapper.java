package vip.fairy.commerceflow.notification.infrastructure.persistence;

import vip.fairy.commerceflow.notification.domain.CustomerNotificationView;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationStoreMapper {

  List<CustomerNotificationView> list(@Param("customerId") Long customerId,
                                      @Param("limit") int limit);

  int unreadCount(Long customerId);

  int markRead(@Param("customerId") Long customerId, @Param("eventId") String eventId);

  int markAllRead(Long customerId);

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

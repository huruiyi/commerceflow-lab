package vip.fairy.commerceflow.commerce.notification.infrastructure;

import java.util.List;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import org.apache.ibatis.annotations.Param;

public interface CustomerNotificationMapper {

  List<CustomerNotification> list(Long customerId);


  int unreadCount(Long customerId);


  int markRead(@Param("customerId") Long customerId, @Param("id") Long id);


  int markAllRead(Long customerId);


  int insertInbox(@Param("consumerName") String consumerName, @Param("eventId") String eventId,
                  @Param("eventType") String eventType, @Param("schemaVersion") int schemaVersion);


  int insertNotification(@Param("eventId") String eventId, @Param("customerId") Long customerId,
                         @Param("orderId") Long orderId, @Param("type") String type,
                         @Param("title") String title, @Param("message") String message);
}

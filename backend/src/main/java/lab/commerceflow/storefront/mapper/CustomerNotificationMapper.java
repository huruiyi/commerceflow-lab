package lab.commerceflow.storefront.mapper;

import java.util.List;
import lab.commerceflow.storefront.domain.CustomerNotification;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CustomerNotificationMapper {
  @Select("""
      SELECT id,order_id,notification_type,title,message,status,read_at,created_at
      FROM customer_notifications
      WHERE customer_id=#{customerId}
      ORDER BY created_at DESC,id DESC
      LIMIT 20
      """)
  List<CustomerNotification> list(Long customerId);

  @Select("SELECT COUNT(*) FROM customer_notifications WHERE customer_id=#{customerId} AND status='UNREAD'")
  int unreadCount(Long customerId);

  @Update("""
      UPDATE customer_notifications SET status='READ',read_at=CURRENT_TIMESTAMP
      WHERE id=#{id} AND customer_id=#{customerId} AND status='UNREAD'
      """)
  int markRead(@Param("customerId") Long customerId, @Param("id") Long id);

  @Update("""
      UPDATE customer_notifications SET status='READ',read_at=CURRENT_TIMESTAMP
      WHERE customer_id=#{customerId} AND status='UNREAD'
      """)
  int markAllRead(Long customerId);
}

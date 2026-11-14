package vip.fairy.commerceflow.commerce.notification.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerNotificationService {

  private final CustomerNotificationMapper notifications;

  public CustomerNotificationService(CustomerNotificationMapper notifications) {
    this.notifications = notifications;
  }

  public List<CustomerNotification> list(Long customerId) {
    return notifications.list(customerId);
  }

  public int unreadCount(Long customerId) {
    return notifications.unreadCount(customerId);
  }

  @Transactional
  public void markRead(Long customerId, Long id) {
    notifications.markRead(customerId, id);
  }

  @Transactional
  public void markAllRead(Long customerId) {
    notifications.markAllRead(customerId);
  }
}

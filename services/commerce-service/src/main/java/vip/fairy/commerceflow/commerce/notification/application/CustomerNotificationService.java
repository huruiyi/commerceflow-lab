package vip.fairy.commerceflow.commerce.notification.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
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

  public List<CustomerNotification> list(AppUser user) {
    return notifications.list(user.getId());
  }

  public int unreadCount(AppUser user) {
    return notifications.unreadCount(user.getId());
  }

  @Transactional
  public void markRead(AppUser user, Long id) {
    notifications.markRead(user.getId(), id);
  }

  @Transactional
  public void markAllRead(AppUser user) {
    notifications.markAllRead(user.getId());
  }
}

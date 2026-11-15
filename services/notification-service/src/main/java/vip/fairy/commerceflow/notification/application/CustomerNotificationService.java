package vip.fairy.commerceflow.notification.application;

import vip.fairy.commerceflow.notification.domain.CustomerNotificationView;
import vip.fairy.commerceflow.notification.infrastructure.persistence.NotificationStoreMapper;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerNotificationService {

  private static final int MAX_LIMIT = 20;

  private final NotificationStoreMapper notifications;

  public CustomerNotificationService(NotificationStoreMapper notifications) {
    this.notifications = notifications;
  }

  public List<CustomerNotificationView> list(Long customerId, int limit) {
    return notifications.list(customerId, normalizeLimit(limit));
  }

  public int unreadCount(Long customerId) {
    return notifications.unreadCount(customerId);
  }

  @Transactional
  public void markRead(Long customerId, String eventId) {
    notifications.markRead(customerId, eventId);
  }

  @Transactional
  public void markAllRead(Long customerId) {
    notifications.markAllRead(customerId);
  }

  private int normalizeLimit(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return Math.min(limit, MAX_LIMIT);
  }
}

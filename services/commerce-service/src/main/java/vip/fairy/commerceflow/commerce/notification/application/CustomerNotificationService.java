package vip.fairy.commerceflow.commerce.notification.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.infrastructure.client.notification.NotificationServiceClient;

import org.springframework.stereotype.Service;

@Service
public class CustomerNotificationService {

  private final NotificationServiceClient client;

  public CustomerNotificationService(NotificationServiceClient client) {
    this.client = client;
  }

  public List<CustomerNotification> list(Long customerId) {
    return client.list(customerId);
  }

  public int unreadCount(Long customerId) {
    return client.unreadCount(customerId);
  }

  public void markRead(Long customerId, String eventId) {
    client.markRead(customerId, eventId);
  }

  public void markAllRead(Long customerId) {
    client.markAllRead(customerId);
  }
}

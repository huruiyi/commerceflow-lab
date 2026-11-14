package vip.fairy.commerceflow.commerce.notification.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.notification.domain.CustomerNotification;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent.StoreMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.storage.NotificationStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CustomerNotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(CustomerNotificationService.class);

  private final NotificationMapper commerceNotifications;
  private final StoreMapper independentNotifications;
  private final NotificationStorageProperties storage;

  public CustomerNotificationService(
    NotificationMapper commerceNotifications,
    ObjectProvider<StoreMapper> independentNotifications,
    NotificationStorageProperties storage
  ) {
    this.commerceNotifications = commerceNotifications;
    this.independentNotifications = independentNotifications.getIfAvailable();
    this.storage = storage;
  }

  public List<CustomerNotification> list(Long customerId) {
    if (storage.usesIndependentReadModel()) {
      return requireIndependent().list(customerId);
    }
    return commerceNotifications.list(customerId);
  }

  public int unreadCount(Long customerId) {
    if (storage.usesIndependentReadModel()) {
      return requireIndependent().unreadCount(customerId);
    }
    return commerceNotifications.unreadCount(customerId);
  }

  public void markRead(Long customerId, Long id) {
    if (storage.usesIndependentReadModel()) {
      StoreMapper independent = requireIndependent();
      String eventId = independent.eventIdById(customerId, id);
      independent.markRead(customerId, id);
      if (eventId != null) {
        mirrorCommerceRead(customerId, eventId);
      }
      return;
    }
    String eventId = commerceNotifications.eventIdById(customerId, id);
    commerceNotifications.markRead(customerId, id);
    if (storage.isEnabled() && eventId != null) {
      mirrorIndependentRead(customerId, eventId);
    }
  }

  public void markAllRead(Long customerId) {
    if (storage.usesIndependentReadModel()) {
      requireIndependent().markAllRead(customerId);
      mirrorCommerceReadAll(customerId);
      return;
    }
    commerceNotifications.markAllRead(customerId);
    if (storage.isEnabled()) {
      mirrorIndependentReadAll(customerId);
    }
  }

  private StoreMapper requireIndependent() {
    if (independentNotifications == null) {
      throw new IllegalStateException("Independent notification storage is not available");
    }
    return independentNotifications;
  }

  private void mirrorIndependentRead(Long customerId, String eventId) {
    try {
      requireIndependent().markReadByEventId(customerId, eventId);
    } catch (RuntimeException exception) {
      LOG.warn("Independent notification read-state mirror failed for event {}", eventId,
        exception);
    }
  }

  private void mirrorIndependentReadAll(Long customerId) {
    try {
      requireIndependent().markAllRead(customerId);
    } catch (RuntimeException exception) {
      LOG.warn("Independent notification read-all mirror failed for customer {}", customerId,
        exception);
    }
  }

  private void mirrorCommerceRead(Long customerId, String eventId) {
    try {
      commerceNotifications.markReadByEventId(customerId, eventId);
    } catch (RuntimeException exception) {
      LOG.warn("Commerce notification rollback mirror failed for event {}", eventId, exception);
    }
  }

  private void mirrorCommerceReadAll(Long customerId) {
    try {
      commerceNotifications.markAllRead(customerId);
    } catch (RuntimeException exception) {
      LOG.warn("Commerce notification rollback read-all mirror failed for customer {}", customerId,
        exception);
    }
  }
}

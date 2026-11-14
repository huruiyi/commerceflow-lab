package vip.fairy.commerceflow.notification.application;

import vip.fairy.commerceflow.notification.infrastructure.persistence.NotificationStoreMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerNotificationCommandHandler {

  public static final String CONSUMER_NAME = "commerceflow-independent-notification-v1";

  private final NotificationStoreMapper notifications;

  public CustomerNotificationCommandHandler(NotificationStoreMapper notifications) {
    this.notifications = notifications;
  }

  @Transactional
  public boolean handle(CustomerNotificationCommandV1 command) {
    CustomerNotificationCommandValidator.validate(command);
    if (notifications.insertInbox(CONSUMER_NAME, command.eventId(), command.eventType(),
      command.schemaVersion(), command.correlationId(), command.causationId(),
      command.traceparent()) != 1) {
      return false;
    }
    notifications.insertNotification(command.eventId(), command.customerId(), command.orderId(),
      command.notificationType(), command.title(), command.message());
    return true;
  }
}

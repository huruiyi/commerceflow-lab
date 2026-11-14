package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerNotificationCommandHandler {

  static final String CONSUMER_NAME = "commerceflow-customer-notification-v1";

  private final NotificationMapper notifications;

  public CustomerNotificationCommandHandler(NotificationMapper notifications) {
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

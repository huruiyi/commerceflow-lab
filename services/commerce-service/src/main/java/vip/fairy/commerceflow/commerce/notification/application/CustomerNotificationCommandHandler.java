package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerNotificationCommandHandler {

  static final String CONSUMER_NAME = "commerceflow-customer-notification-v1";

  private final CustomerNotificationMapper notifications;

  public CustomerNotificationCommandHandler(CustomerNotificationMapper notifications) {
    this.notifications = notifications;
  }

  @Transactional
  public boolean handle(CustomerNotificationCommandV1 command) {
    validate(command);
    if (notifications.insertInbox(CONSUMER_NAME, command.eventId(), command.eventType(),
      command.schemaVersion(), command.correlationId(), command.causationId(),
      command.traceparent()) != 1) {
      return false;
    }
    notifications.insertNotification(command.eventId(), command.customerId(), command.orderId(),
      command.notificationType(), command.title(), command.message());
    return true;
  }

  private void validate(CustomerNotificationCommandV1 command) {
    if (command == null || command.schemaVersion() != CustomerNotificationCommandV1.VERSION ||
        !CustomerNotificationCommandV1.TYPE.equals(command.eventType())) {
      throw new IllegalArgumentException("Unsupported customer notification command contract");
    }
    require(command.eventId(), "eventId");
    require(command.notificationType(), "notificationType");
    require(command.title(), "title");
    require(command.message(), "message");
    require(command.correlationId(), "correlationId");
    require(command.causationId(), "causationId");
    require(command.traceparent(), "traceparent");
    if (!CustomerNotificationCommandV1.TRACEPARENT.matcher(command.traceparent()).matches()) {
      throw new IllegalArgumentException("Customer notification command has an invalid traceparent");
    }
    if (command.occurredAt() == null || command.customerId() == null || command.orderId() == null) {
      throw new IllegalArgumentException("Customer notification command is missing required facts");
    }
  }

  private void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Customer notification command is missing " + field);
    }
  }
}

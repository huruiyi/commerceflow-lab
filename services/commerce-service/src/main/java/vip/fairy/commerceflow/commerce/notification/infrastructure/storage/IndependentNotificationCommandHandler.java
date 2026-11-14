package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandValidator;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent.StoreMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class IndependentNotificationCommandHandler {

  static final String CONSUMER_NAME = "commerceflow-independent-notification-v1";

  private final StoreMapper notifications;

  public IndependentNotificationCommandHandler(StoreMapper notifications) {
    this.notifications = notifications;
  }

  @Transactional(transactionManager = "notificationTransactionManager")
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

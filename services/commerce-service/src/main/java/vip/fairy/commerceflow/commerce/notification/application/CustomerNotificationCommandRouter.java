package vip.fairy.commerceflow.commerce.notification.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.NotificationDeliveryProperties;

import org.springframework.stereotype.Service;

@Service
public class CustomerNotificationCommandRouter implements CustomerNotificationCommandPublisher {

  private final CustomerNotificationCommandHandler handler;
  private final CustomerNotificationMapper notifications;
  private final NotificationDeliveryProperties properties;
  private final ObjectMapper mapper;

  public CustomerNotificationCommandRouter(CustomerNotificationCommandHandler handler,
                                           CustomerNotificationMapper notifications,
                                           NotificationDeliveryProperties properties,
                                           ObjectMapper mapper) {
    this.handler = handler;
    this.notifications = notifications;
    this.properties = properties;
    this.mapper = mapper;
  }

  @Override
  public void publish(CustomerNotificationCommandV1 command) {
    if (properties.getMode() == NotificationDeliveryProperties.Mode.IN_PROCESS) {
      handler.handle(command);
      return;
    }
    try {
      notifications.insertOutbox(command.eventId(), properties.getTopic(),
        mapper.writeValueAsString(command));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Customer notification command cannot be serialized", exception);
    }
  }
}

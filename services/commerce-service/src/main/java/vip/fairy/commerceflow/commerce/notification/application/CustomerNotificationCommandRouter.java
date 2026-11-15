package vip.fairy.commerceflow.commerce.notification.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.NotificationDeliveryProperties;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;

import org.springframework.stereotype.Service;

@Service
public class CustomerNotificationCommandRouter implements CustomerNotificationCommandPublisher {

  private final NotificationMapper notifications;
  private final NotificationDeliveryProperties properties;
  private final ObjectMapper mapper;
  private final TraceContextProvider traceContext;

  public CustomerNotificationCommandRouter(NotificationMapper notifications,
                                           NotificationDeliveryProperties properties,
                                           ObjectMapper mapper,
                                           TraceContextProvider traceContext) {
    this.notifications = notifications;
    this.properties = properties;
    this.mapper = mapper;
    this.traceContext = traceContext;
  }

  @Override
  public void publish(CustomerNotificationCommandV1 command) {
    try {
      notifications.insertOutbox(command.eventId(), properties.getTopic(),
        mapper.writeValueAsString(command));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Customer notification command cannot be serialized", exception);
    }
  }

  @Override
  public void publishOrderCancelled(String eventId, Long customerId, Long orderId, String reason,
                                    String causationId) {
    publish(CustomerNotificationCommandV1.orderCancelled(
      eventId, customerId, orderId, reason, causationId, traceContext.traceparent()));
  }
}

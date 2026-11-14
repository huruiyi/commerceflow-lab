package vip.fairy.commerceflow.commerce.notification.infrastructure.projection;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationKafkaHeaders;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerNotificationProjectionConsumer {

  static final String CONSUMER_GROUP = "commerceflow-customer-notification-shadow-v1";

  private final CustomerNotificationMapper notifications;
  private final ObjectMapper mapper;

  public CustomerNotificationProjectionConsumer(CustomerNotificationMapper notifications,
                                                ObjectMapper mapper) {
    this.notifications = notifications;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${commerceflow.notification.delivery.topic:customer-notification-commands}",
    groupId = CONSUMER_GROUP, containerFactory = "notificationKafkaListenerContainerFactory",
    autoStartup = "${commerceflow.notification.projection.enabled:false}")
  public void consume(ConsumerRecord<String, String> record) throws Exception {
    CustomerNotificationCommandV1 command = mapper.readValue(record.value(),
      CustomerNotificationCommandV1.class);
    CustomerNotificationKafkaHeaders.validate(record, command);
    Long windowId = notifications.runningProjectionWindowId();
    if (windowId == null) {
      return;
    }
    notifications.insertProjection(windowId, command.eventId(), command.customerId(), command.orderId(),
      command.notificationType(), command.title(), command.message(), command.correlationId(),
      command.causationId(), command.traceparent());
  }
}

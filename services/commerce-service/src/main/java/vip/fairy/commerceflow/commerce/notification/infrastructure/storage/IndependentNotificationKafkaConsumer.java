package vip.fairy.commerceflow.commerce.notification.infrastructure.storage;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationKafkaHeaders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class IndependentNotificationKafkaConsumer {

  static final String CONSUMER_GROUP = "commerceflow-independent-notification-v1";

  private final IndependentNotificationCommandHandler handler;
  private final ObjectMapper mapper;

  public IndependentNotificationKafkaConsumer(IndependentNotificationCommandHandler handler,
                                              ObjectMapper mapper) {
    this.handler = handler;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${commerceflow.notification.delivery.topic:customer-notification-commands}",
    groupId = CONSUMER_GROUP, containerFactory = "notificationKafkaListenerContainerFactory",
    autoStartup = "${commerceflow.notification.storage.consumer-enabled:false}")
  public void consume(ConsumerRecord<String, String> record) throws Exception {
    CustomerNotificationCommandV1 command = mapper.readValue(record.value(),
      CustomerNotificationCommandV1.class);
    CustomerNotificationKafkaHeaders.validate(record, command);
    handler.handle(command);
  }
}

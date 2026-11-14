package vip.fairy.commerceflow.commerce.notification.infrastructure;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandHandler;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerNotificationKafkaConsumer {

  static final String CONSUMER_GROUP = "commerceflow-customer-notification-v1";

  private final CustomerNotificationCommandHandler handler;
  private final ObjectMapper mapper;

  public CustomerNotificationKafkaConsumer(CustomerNotificationCommandHandler handler,
                                           ObjectMapper mapper) {
    this.handler = handler;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${commerceflow.notification.delivery.topic:customer-notification-commands}",
    groupId = CONSUMER_GROUP, containerFactory = "notificationKafkaListenerContainerFactory")
  public void consume(ConsumerRecord<String, String> record) throws Exception {
    CustomerNotificationCommandV1 command = mapper.readValue(record.value(),
      CustomerNotificationCommandV1.class);
    CustomerNotificationKafkaHeaders.validate(record, command);
    handler.handle(command);
  }
}

package vip.fairy.commerceflow.notification.infrastructure.messaging;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandHandler;
import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandV1;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerNotificationKafkaConsumer {

  private final CustomerNotificationCommandHandler handler;
  private final ObjectMapper mapper;

  public CustomerNotificationKafkaConsumer(CustomerNotificationCommandHandler handler,
                                           ObjectMapper mapper) {
    this.handler = handler;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${commerceflow.notification.topic:customer-notification-commands}",
    groupId = "${commerceflow.notification.consumer-group:commerceflow-independent-notification-v1}",
    containerFactory = "notificationKafkaListenerContainerFactory")
  public void consume(ConsumerRecord<String, String> record) throws Exception {
    CustomerNotificationCommandV1 command = mapper.readValue(record.value(),
      CustomerNotificationCommandV1.class);
    command = CustomerNotificationKafkaHeaders.validate(record, command);
    handler.handle(command);
  }
}

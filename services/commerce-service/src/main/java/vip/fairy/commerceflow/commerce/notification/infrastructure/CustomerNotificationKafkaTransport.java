package vip.fairy.commerceflow.commerce.notification.infrastructure;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CustomerNotificationKafkaTransport {

  static final String CORRELATION_HEADER = "commerceflow-correlation-id";
  static final String CAUSATION_HEADER = "commerceflow-causation-id";
  static final String TRACEPARENT_HEADER = "traceparent";

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;

  public CustomerNotificationKafkaTransport(KafkaTemplate<String, String> kafka,
                                            ObjectMapper mapper) {
    this.kafka = kafka;
    this.mapper = mapper;
  }

  public void send(String topic, CustomerNotificationCommandV1 command) throws Exception {
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, command.eventId(),
      serialize(command));
    header(record, CORRELATION_HEADER, command.correlationId());
    header(record, CAUSATION_HEADER, command.causationId());
    header(record, TRACEPARENT_HEADER, command.traceparent());
    kafka.send(record).get(5, TimeUnit.SECONDS);
  }

  private String serialize(CustomerNotificationCommandV1 command) {
    try {
      return mapper.writeValueAsString(command);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Customer notification command cannot be serialized", exception);
    }
  }

  private void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}

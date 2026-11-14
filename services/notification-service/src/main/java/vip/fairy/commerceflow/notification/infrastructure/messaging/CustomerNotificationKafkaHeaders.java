package vip.fairy.commerceflow.notification.infrastructure.messaging;

import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandV1;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

public final class CustomerNotificationKafkaHeaders {

  public static final String CORRELATION_HEADER = "commerceflow-correlation-id";
  public static final String CAUSATION_HEADER = "commerceflow-causation-id";
  public static final String ORIGIN_TRACEPARENT_HEADER = "commerceflow-origin-traceparent";
  public static final String TRACEPARENT_HEADER = "traceparent";

  private CustomerNotificationKafkaHeaders() {
  }

  public static CustomerNotificationCommandV1 validate(ConsumerRecord<String, String> record,
                                                       CustomerNotificationCommandV1 command) {
    require(record, CORRELATION_HEADER, command.correlationId());
    require(record, CAUSATION_HEADER, command.causationId());
    String originTraceparent = value(record, ORIGIN_TRACEPARENT_HEADER);
    String transportTraceparent = value(record, TRACEPARENT_HEADER);
    if (originTraceparent == null) {
      if (!command.traceparent().equals(transportTraceparent)) {
        throw new IllegalArgumentException(
          "Legacy customer notification command has invalid traceparent");
      }
      return command;
    }
    if (!command.traceparent().equals(originTraceparent)) {
      throw new IllegalArgumentException(
        "Customer notification command has invalid commerceflow-origin-traceparent");
    }
    if (transportTraceparent == null
        || !CustomerNotificationCommandV1.TRACEPARENT.matcher(transportTraceparent).matches()) {
      throw new IllegalArgumentException("Customer notification command has invalid traceparent");
    }
    return command.withTraceparent(transportTraceparent);
  }

  private static void require(ConsumerRecord<String, String> record, String name,
                              String expected) {
    String actual = value(record, name);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Customer notification command has invalid " + name);
    }
  }

  private static String value(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}

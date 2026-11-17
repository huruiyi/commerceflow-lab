package vip.fairy.commerceflow.commerce.payment.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.domain.OutboxEvent;
import vip.fairy.commerceflow.commerce.payment.infrastructure.OutboxMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventPipeline {

  private final OutboxMapper outbox;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;

  public PaymentEventPipeline(OutboxMapper o, KafkaTemplate<String, String> k, ObjectMapper m) {
    outbox = o;
    kafka = k;
    mapper = m;
  }

  public void append(String aggregateId, String type, String payload) {
    append("PAYMENT", aggregateId, type, payload);
  }

  private String append(String aggregateType, String aggregateId, String type, String payload) {
    String eventId = UUID.randomUUID().toString();
    outbox.insert(eventId, aggregateType, aggregateId, type, topic(type), payload);
    return eventId;
  }

  @Scheduled(
    fixedDelayString = "${commerceflow.outbox.delay:1000}",
    initialDelayString = "${commerceflow.outbox.initial-delay:1000}"
  )
  public void publish() {
    for (OutboxEvent e : outbox.pending())
      try {
        kafka.send(e.getTopic(), e.getAggregateId(), envelope(e)).get(5, TimeUnit.SECONDS);
        outbox.published(e.getId());
      } catch (Exception ex) {
        outbox.retry(e.getId());
      }
  }

  @KafkaListener(
    topics = {
      "payment-events",
      "payment-callback-events",
      "payment-reconciliation-events",
      "merchant-notification-events"
    },
    groupId = "commerceflow-payment-projection"
  )
  public void consume(ConsumerRecord<String, String> r) throws Exception {
    JsonNode n = mapper.readTree(r.value());
    outbox.consume("commerceflow-payment-projection", n.get("eventId").asText(), r.topic());
  }

  private String envelope(OutboxEvent e) throws Exception {
    return mapper.writeValueAsString(Map.of(
      "eventId", e.getEventId(),
      "eventType", e.getEventType(),
      "aggregateId", e.getAggregateId(),
      "payload", mapper.readTree(e.getPayload())
    ));
  }

  private String topic(String t) {
    if (t.contains("CALLBACK")) return "payment-callback-events";
    if (t.contains("RECONCIL")) return "payment-reconciliation-events";
    if (t.contains("MERCHANT")) return "merchant-notification-events";
    return "payment-events";
  }
}

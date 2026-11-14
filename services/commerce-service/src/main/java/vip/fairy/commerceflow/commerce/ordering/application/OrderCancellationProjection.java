package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.inventory.application.InventoryService;
import vip.fairy.commerceflow.commerce.payment.infrastructure.OutboxMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderCancellationProjection {

  static final String CONSUMER_GROUP = "commerceflow-order-effects";

  private final ObjectMapper mapper;
  private final OutboxMapper outbox;
  private final InventoryService inventory;

  public OrderCancellationProjection(ObjectMapper mapper, OutboxMapper outbox,
                                     InventoryService inventory) {
    this.mapper = mapper;
    this.outbox = outbox;
    this.inventory = inventory;
  }

  @KafkaListener(topics = "order-events", groupId = CONSUMER_GROUP)
  public void consume(ConsumerRecord<String, String> record) throws Exception {
    process(record.topic(), record.value());
  }

  @Transactional
  public void process(String topic, String envelope) throws Exception {
    JsonNode event = mapper.readTree(envelope);
    String eventId = required(event, "eventId");
    if (outbox.consume(CONSUMER_GROUP, eventId, topic) != 1) {
      return;
    }
    if (!"ORDER_CANCELLED".equals(required(event, "eventType"))) {
      return;
    }
    Long orderId = Long.valueOf(required(event, "aggregateId"));
    JsonNode payload = event.get("payload");
    String reason = payload != null && payload.hasNonNull("reason")
      ? payload.get("reason").asText()
      : "Order cancelled";
    inventory.releaseCancelledOrder(orderId, eventId, reason);
  }

  private String required(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
      throw new IllegalArgumentException("Order event is missing " + field);
    }
    return node.get(field).asText();
  }
}

package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.payment.infrastructure.OutboxMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderCancellationProjection implements InventoryCommandPort.StateProjection {

  static final String CONSUMER_GROUP = "commerceflow-order-effects";

  private final ObjectMapper mapper;
  private final OutboxMapper outbox;
  private final InventoryCommandPort inventory;
  private final ShopOrderMapper orders;

  public OrderCancellationProjection(ObjectMapper mapper, OutboxMapper outbox,
                                     InventoryCommandPort inventory, ShopOrderMapper orders) {
    this.mapper = mapper;
    this.outbox = outbox;
    this.inventory = inventory;
    this.orders = orders;
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
    inventory.handle(InventoryCommandPort.release(
      eventId + ":inventory:release", orderId, reason, eventId));
  }

  @Override
  public void succeeded(InventoryCommandV1 command) {
    String current = orders.lockById(command.orderId()).orElseThrow().getInventoryStatus();
    String target = switch (command.commandType()) {
      case RESERVE -> "RESERVED";
      case COMMIT -> "COMMITTED";
      case RELEASE -> "RELEASED";
      case REFUND -> "REFUNDED";
    };
    if (current.equals(target)) {
      return;
    }
    if (command.commandType() == vip.fairy.commerceflow.commerce.inventory.application.contract.v1
        .InventoryCommandTypeV1.RESERVE && "WAITING_COMMIT".equals(current)) {
      return;
    }
    requireTransition(command.orderId(), current, target);
  }

  @Override
  public void reconciliationRequired(InventoryCommandV1 command) {
    String current = orders.lockById(command.orderId()).orElseThrow().getInventoryStatus();
    if (!"RECONCILIATION_REQUIRED".equals(current)) {
      requireTransition(command.orderId(), current, "RECONCILIATION_REQUIRED");
    }
  }

  private void requireTransition(Long orderId, String fromStatus, String toStatus) {
    if (orders.updateInventoryStatus(orderId, fromStatus, toStatus) != 1) {
      throw new IllegalStateException("Order inventory recovery state changed concurrently");
    }
  }

  private String required(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
      throw new IllegalArgumentException("Order event is missing " + field);
    }
    return node.get(field).asText();
  }
}

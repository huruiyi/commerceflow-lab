package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEffectInboxPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
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
  private final OrderEffectInboxPort inbox;
  private final InventoryCommandPort inventory;
  private final OrderStorePort orders;

  public OrderCancellationProjection(ObjectMapper mapper, OrderEffectInboxPort inbox,
                                     InventoryCommandPort inventory, OrderStorePort orders) {
    this.mapper = mapper;
    this.inbox = inbox;
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
    if (!inbox.claim(CONSUMER_GROUP, eventId, topic)) {
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
    String target = target(command, current);
    if (target == null || current.equals(target)) return;
    requireTransition(command.orderId(), current, target);
  }

  private String target(InventoryCommandV1 command, String current) {
    return switch (command.commandType()) {
      case RESERVE -> "WAITING_RESERVATION".equals(current)
        || "RECONCILIATION_REQUIRED".equals(current) ? "RESERVED" : null;
      case COMMIT -> "WAITING_COMMIT".equals(current) || "RESERVED".equals(current)
        || "RECONCILIATION_REQUIRED".equals(current)
        ? "COMMITTED"
        : null;
      case RELEASE -> "WAITING_RESERVATION".equals(current) || "RESERVED".equals(current)
        || "RECONCILIATION_REQUIRED".equals(current)
        ? "RELEASED"
        : null;
      case REFUND -> "REFUND_PENDING".equals(current)
        || "RECONCILIATION_REQUIRED".equals(current) ? "REFUNDED" : null;
    };
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

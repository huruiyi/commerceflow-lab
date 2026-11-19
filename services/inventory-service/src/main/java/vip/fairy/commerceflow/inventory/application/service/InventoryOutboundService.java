package vip.fairy.commerceflow.inventory.application.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Document;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Event;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Line;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Receipt;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryLineCreditMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryOutboundMapper;

@Service
public class InventoryOutboundService {

  private final InventoryMapper inventory;
  private final InventoryLineCreditMapper credits;
  private final InventoryOutboundMapper store;
  private final ObjectMapper mapper;

  public InventoryOutboundService(InventoryMapper inventory, InventoryOutboundMapper store, ObjectMapper mapper,
                                  InventoryLineCreditMapper credits) {
    this.inventory = inventory;
    this.credits = credits;
    this.store = store;
    this.mapper = mapper;
  }

  @Transactional
  public Receipt post(Document requested) {
    List<Line> lines = validate(requested);
    String id = requested.outboundId().toString();
    Document document = new Document(requested.outboundId(), requested.orderId(), requested.merchantId(), lines);
    String digest = DeliveredInventoryReconciliationService.hash(mapper.writeValueAsString(document));
    Receipt receipt = new Receipt(document.outboundId(), document.orderId(), lines.size());
    if (store.claim(id, document.orderId(), document.merchantId(), digest) == 0) {
      if (!digest.equals(store.fingerprint(id))) {
        throw new InventoryBusinessRuleException("Outbound document conflicts with an existing shipment");
      }
      return receipt;
    }
    requireCommittedQuantities(document.orderId(), lines);
    Instant occurredAt = Instant.now();
    try {
      for (Line line : lines) {
        postLine(document, line, occurredAt);
      }
    } catch (DuplicateKeyException exception) {
      throw new InventoryBusinessRuleException("Order line is already posted");
    }
    return receipt;
  }

  private void requireCommittedQuantities(long orderId, List<Line> lines) {
    Map<Long, Integer> allocated = new HashMap<>();
    for (var reservation : inventory.lockByOrder(orderId)) {
      if (!"COMMITTED".equals(reservation.status())) {
        throw new InventoryBusinessRuleException("Outbound posting requires committed inventory");
      }
      int remaining = reservation.quantity()
        - credits.creditedProduct(orderId, reservation.productId(), "BEFORE_SHIPMENT");
      if (remaining < 0) throw new InventoryBusinessRuleException("Line refunds exceed committed inventory");
      if (remaining > 0) allocated.put(reservation.productId(), remaining);
    }
    Map<Long, Integer> shipped = new HashMap<>();
    lines.forEach(line -> shipped.merge(line.productId(), line.quantity(), Math::addExact));
    if (!allocated.equals(shipped)) {
      throw new InventoryBusinessRuleException("Outbound quantities do not match committed inventory");
    }
  }

  private void postLine(Document document, Line line, Instant occurredAt) {
    String id = document.outboundId().toString();
    String eventId = "outbound:" + id + ":" + line.orderLineId();
    store.insertLine(id, line);
    inventory.insertMovement(eventId, document.orderId(), line.productId(), "OUTBOUND_POSTED",
      line.quantity(), inventory.lockInventory(line.productId()), "Outbound document " + id);
    Event event = new Event(1, "INVENTORY_OUTBOUND_POSTED", eventId, document.outboundId(),
      document.orderId(), document.merchantId(), line.orderLineId(), line.productId(), line.quantity(), occurredAt);
    store.enqueue(eventId, id, line.orderLineId(), mapper.writeValueAsString(event));
  }

  private List<Line> validate(Document document) {
    if (document == null || document.outboundId() == null || document.orderId() <= 0
        || document.merchantId() <= 0 || document.lines() == null || document.lines().isEmpty()) {
      throw new IllegalArgumentException("Outbound document is incomplete");
    }
    Set<Long> orderLines = new HashSet<>();
    for (Line line : document.lines()) {
      if (line == null || line.orderLineId() <= 0 || line.productId() <= 0
          || line.quantity() <= 0 || !orderLines.add(line.orderLineId())) {
        throw new IllegalArgumentException("Outbound order line is invalid or duplicated");
      }
    }
    return document.lines().stream()
      .sorted(Comparator.comparingLong(Line::productId).thenComparingLong(Line::orderLineId)).toList();
  }
}

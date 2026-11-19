package vip.fairy.commerceflow.inventory.application.service;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryLineCreditMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;

@Service
public class InventoryLineCreditService {

  private final InventoryLineCreditMapper credits;
  private final InventoryMapper inventory;

  public InventoryLineCreditService(InventoryLineCreditMapper credits, InventoryMapper inventory) {
    this.credits = credits;
    this.inventory = inventory;
  }

  @Transactional
  public boolean credit(Credit value, boolean apply) {
    if (value == null || value.caseId() == null || !value.caseId().matches("[0-9a-f-]{36}")
        || value.orderId() == null || value.orderLineId() == null || value.productId() == null
        || value.merchantId() == null || value.quantity() <= 0 || value.kind() == null
        || !Set.of("BEFORE_SHIPMENT", "RETURN").contains(value.kind())) {
      throw new IllegalArgumentException("Invalid after-sales inventory credit");
    }
    var reservation = inventory.lockByOrder(value.orderId()).stream()
      .filter(item -> item.productId().equals(value.productId()) && "COMMITTED".equals(item.status()))
      .findFirst().orElse(null);
    var existing = credits.find(value.caseId());
    if (existing != null) return existing.equals(value);
    if (reservation == null) return false;
    int creditedBefore = credits.creditedProduct(value.orderId(), value.productId(), "BEFORE_SHIPMENT");
    int creditedReturn = credits.creditedProduct(value.orderId(), value.productId(), "RETURN");
    if (value.quantity() > reservation.quantity() - creditedBefore - creditedReturn) return false;
    boolean returned = "RETURN".equals(value.kind());
    if (returned) {
      int outboundQuantity = credits.outboundQuantity(value);
      int alreadyCredited = credits.creditedLine(value.orderId(), value.orderLineId());
      if (value.quantity() > outboundQuantity - alreadyCredited) {
        return false;
      }
    } else if (value.quantity() > reservation.quantity() - creditedBefore - credits.outboundQuantity(value)) {
      return false;
    }
    if (!apply) return true;
    if (inventory.refund(value.productId(), value.quantity()) != 1) {
      throw new IllegalStateException("Sold inventory is inconsistent for after-sales credit");
    }
    credits.insert(value);
    inventory.insertMovement("after-sale:" + value.caseId(), value.orderId(), value.productId(),
      returned ? "RETURN_RESTOCKED" : "UNSHIPPED_REFUND_RESTOCKED", value.quantity(),
      inventory.lockInventory(value.productId()), "Inventory credit for after-sales case " + value.caseId());
    return true;
  }

  public record Credit(String caseId, Long orderId, Long orderLineId, Long merchantId, Long productId,
                       int quantity, String kind) {
  }
}

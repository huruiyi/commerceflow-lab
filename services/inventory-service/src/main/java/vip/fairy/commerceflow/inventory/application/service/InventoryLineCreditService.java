package vip.fairy.commerceflow.inventory.application.service;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryLineCreditMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.WarehouseStockMapper;

@Service
public class InventoryLineCreditService {

  private final InventoryLineCreditMapper credits;
  private final InventoryMapper inventory;
  private final WarehouseStockMapper warehouses;

  public InventoryLineCreditService(InventoryLineCreditMapper credits, InventoryMapper inventory,
                                    WarehouseStockMapper warehouses) {
    this.credits = credits;
    this.inventory = inventory;
    this.warehouses = warehouses;
  }

  public List<Source> sources(Origin request) {
    if (request.orderId() <= 0 || request.orderLineId() <= 0 || request.merchantId() <= 0
        || request.productId() <= 0
        || (request.outboundId() != null && !request.outboundId().matches("[0-9a-f-]{36}"))
        || (request.warehouseId() != null && request.warehouseId() <= 0)) {
      throw new IllegalArgumentException("Invalid return source query");
    }
    return credits.sources(request);
  }

  @Transactional
  public boolean credit(Credit value, boolean apply) {
    if (value == null || value.caseId() == null || !value.caseId().matches("[0-9a-f-]{36}")
        || value.orderId() == null || value.orderLineId() == null || value.productId() == null
        || value.merchantId() == null || value.quantity() <= 0 || value.kind() == null
        || !Set.of("BEFORE_SHIPMENT", "RETURN").contains(value.kind()) || value.warehouse() <= 0
        || (value.outboundId() != null && (!"RETURN".equals(value.kind())
          || !value.outboundId().matches("[0-9a-f-]{36}")))) {
      throw new IllegalArgumentException("Invalid after-sales inventory credit");
    }
    var reservations = inventory.lockByOrder(value.orderId());
    var reservation = reservations.stream()
      .filter(item -> item.productId().equals(value.productId()) && "COMMITTED".equals(item.status())
        && item.warehouseId() == value.warehouse())
      .findFirst().orElse(null);
    var existing = credits.find(value.caseId());
    if (existing != null) return existing.equals(value);
    if (reservation == null) return false;
    boolean returned = "RETURN".equals(value.kind());
    boolean warehouseOrder = reservations.stream().anyMatch(item -> item.warehouseId() != 1);
    if (warehouseOrder && (value.warehouseId() == null || (returned && value.outboundId() == null))) return false;
    int creditedBefore = credits.creditedProduct(value.orderId(), value.productId(), "BEFORE_SHIPMENT",
      value.warehouse());
    int creditedReturn = credits.creditedProduct(value.orderId(), value.productId(), "RETURN", value.warehouse());
    if (value.quantity() > reservation.quantity() - creditedBefore - creditedReturn) return false;
    if (returned) {
      int outboundQuantity = credits.outboundQuantity(value);
      int alreadyCredited = credits.creditedLine(value);
      if (value.quantity() > outboundQuantity - alreadyCredited) {
        return false;
      }
    } else if (value.quantity() > reservation.quantity() - creditedBefore - credits.outboundQuantity(value)) {
      return false;
    }
    if (!apply) return true;
    if (inventory.refund(value.productId(), value.quantity()) != 1
        || warehouses.adjust(value.productId(), value.warehouse(), value.quantity(), 0, -value.quantity()) != 1) {
      throw new IllegalStateException("Sold inventory is inconsistent for after-sales credit");
    }
    credits.insert(value);
    inventory.insertWarehouseMovement("after-sale:" + value.caseId(), value.orderId(), value.productId(),
      returned ? "RETURN_RESTOCKED" : "UNSHIPPED_REFUND_RESTOCKED", value.quantity(),
      inventory.lockInventory(value.productId()), "Inventory credit for after-sales case " + value.caseId(),
      value.warehouse());
    return true;
  }

  public record Origin(long orderId, long orderLineId, long merchantId, long productId,
                       Long warehouseId, String outboundId) {
  }

  public record Source(long warehouseId, String outboundId, int quantity) {
  }

  public record Credit(String caseId, Long orderId, Long orderLineId, Long merchantId, Long productId,
                       int quantity, String kind, Long warehouseId, String outboundId) {

    public long warehouse() {
      return warehouseId == null ? 1 : warehouseId;
    }
  }
}

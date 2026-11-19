package vip.fairy.commerceflow.inventory.application.service;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.inventory.domain.ProductInventory;
import vip.fairy.commerceflow.inventory.domain.StockAvailability;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.WarehouseStockMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.WarehouseStockMapper.AvailableStock;

@Service
public class InventoryBalanceService {

  private final InventoryMapper inventory;
  private final WarehouseStockMapper warehouses;

  public InventoryBalanceService(InventoryMapper inventory, WarehouseStockMapper warehouses) {
    this.inventory = inventory;
    this.warehouses = warehouses;
  }

  @Transactional(propagation = Propagation.NESTED)
  public void reserve(Long orderId, List<InventoryCommandV1.Item> items) {
    Map<Long, Integer> demand = new LinkedHashMap<>();
    items.stream().sorted(Comparator.comparing(InventoryCommandV1.Item::productId))
      .forEach(item -> demand.merge(item.productId(), item.quantity(), Math::addExact));
    for (long product : demand.keySet()) {
      if (inventory.lockInventory(product) == null) throw new InventoryReservationRejectedException(product);
    }
    var available = new ArrayList<>(warehouses.lockAvailable(List.copyOf(demand.keySet())));
    available.sort(Comparator.comparingInt(AvailableStock::priority).thenComparingLong(AvailableStock::warehouseId));
    Long wholeWarehouse = wholeWarehouse(demand, available);
    for (var entry : demand.entrySet()) {
      long productId = entry.getKey();
      int remaining = entry.getValue();
      for (var stock : available) {
        if (stock.productId() != productId || (wholeWarehouse != null && wholeWarehouse != stock.warehouseId())) {
          continue;
        }
        int quantity = Math.min(remaining, stock.available());
        if (quantity > 0) {
          reserveAtWarehouse(orderId, productId, stock.warehouseId(), quantity);
          remaining -= quantity;
        }
        if (remaining == 0) break;
      }
      if (remaining > 0) throw new InventoryReservationRejectedException(productId);
    }
  }

  private Long wholeWarehouse(Map<Long, Integer> demand, List<AvailableStock> available) {
    return available.stream().map(AvailableStock::warehouseId).distinct().filter(warehouseId ->
      available.stream().filter(stock -> stock.warehouseId() == warehouseId
        && stock.available() >= demand.get(stock.productId())).count() == demand.size())
      .findFirst().orElse(null);
  }

  private void reserveAtWarehouse(Long orderId, Long productId, long warehouseId, int quantity) {
    if (inventory.reserve(productId, quantity) != 1
        || warehouses.adjust(productId, warehouseId, -quantity, quantity, 0) != 1) {
      throw new InventoryReservationRejectedException(productId);
    }
    inventory.insertReservation(orderId, productId, quantity, warehouseId);
    movement("reserve:" + UUID.randomUUID(), orderId, productId, "RESERVED",
      quantity, "Stock reserved during checkout", warehouseId);
  }

  public Map<Long, Integer> activeReservations(Long orderId) {
    return activeAllocations(orderId).stream()
      .collect(Collectors.toMap(InventoryReservation::productId, InventoryReservation::quantity, Math::addExact));
  }

  public List<InventoryReservation> activeAllocations(Long orderId) {
    return inventory.activeReservations(orderId);
  }

  public List<StockAvailability> availability(List<Long> productIds) {
    return productIds.isEmpty() ? List.of() : inventory.availability(productIds);
  }

  public void requireCommittedReservations(Long orderId) {
    if (!inventory.hasCommittedReservations(orderId)) {
      throw new InventoryBusinessRuleException("No completed stock commitment for order " + orderId);
    }
  }

  public void commit(Long orderId, String eventId) {
    List<InventoryReservation> reservations = inventory.lockByOrder(orderId);
    if (reservations.isEmpty()) {
      throw new InventoryBusinessRuleException("No inventory reservation for order " + orderId);
    }
    if (inventory.hasMissingReservationLine(orderId)) {
      throw new InventoryBusinessRuleException("Incomplete inventory reservations for order " + orderId);
    }
    for (InventoryReservation reservation : reservations) {
      if ("COMMITTED".equals(reservation.status())) {
        continue;
      }
      String movementType;
      if ("RESERVED".equals(reservation.status())) {
        requireChanged(inventory.commitReserved(reservation.productId(), reservation.quantity()),
          "Reserved stock is inconsistent");
        movementType = "COMMITTED";
        adjustWarehouse(reservation, 0, -reservation.quantity(), reservation.quantity());
      } else if ("RELEASED".equals(reservation.status())) {
        requireChanged(inventory.commitReleased(reservation.productId(), reservation.quantity()),
          "Stock is unavailable after a late successful payment");
        movementType = "LATE_PAYMENT_COMMITTED";
        adjustWarehouse(reservation, -reservation.quantity(), 0, reservation.quantity());
      } else {
        throw new InventoryBusinessRuleException("Cannot commit reservation in state "
          + reservation.status());
      }
      requireChanged(inventory.markCommitted(reservation.id(), reservation.status()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":commit:" + reservation.id(), orderId, reservation.productId(),
        movementType, reservation.quantity(), "Payment completed the inventory reservation", reservation.warehouseId());
    }
  }

  public void release(Long orderId, String eventId, String reason) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"RESERVED".equals(reservation.status())) {
        continue;
      }
      requireChanged(inventory.release(reservation.productId(), reservation.quantity()),
        "Reserved stock is inconsistent");
      adjustWarehouse(reservation, reservation.quantity(), -reservation.quantity(), 0);
      requireChanged(inventory.markReleased(reservation.id()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":release:" + reservation.id(), orderId, reservation.productId(),
        "RELEASED", reservation.quantity(), reason, reservation.warehouseId());
    }
  }

  public void refund(Long orderId, String eventId) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"COMMITTED".equals(reservation.status())) {
        continue;
      }
      requireChanged(inventory.refund(reservation.productId(), reservation.quantity()),
        "Sold stock is inconsistent");
      adjustWarehouse(reservation, reservation.quantity(), 0, -reservation.quantity());
      requireChanged(inventory.markRefunded(reservation.id()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":refund:" + reservation.id(), orderId, reservation.productId(),
        "REFUNDED", reservation.quantity(), "Successful refund returned sold stock", reservation.warehouseId());
    }
  }

  private void adjustWarehouse(InventoryReservation reservation, int available, int reserved, int sold) {
    requireChanged(warehouses.adjust(reservation.productId(), reservation.warehouseId(), available, reserved, sold),
      "Allocated warehouse stock is inconsistent");
  }

  private void movement(String eventId, Long orderId, Long productId, String type,
                        int quantity, String detail, long warehouseId) {
    ProductInventory balance = inventory.inventory(productId);
    if (balance == null) {
      throw new IllegalStateException("Product inventory not found");
    }
    inventory.insertWarehouseMovement(eventId, orderId, productId, type, quantity, balance, detail, warehouseId);
  }

  private void requireChanged(int changed, String message) {
    if (changed != 1) {
      throw new IllegalStateException(message);
    }
  }
}

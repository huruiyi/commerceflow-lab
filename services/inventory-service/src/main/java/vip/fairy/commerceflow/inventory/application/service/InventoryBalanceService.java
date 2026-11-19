package vip.fairy.commerceflow.inventory.application.service;

import java.util.List;
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

@Service
public class InventoryBalanceService {

  private final InventoryMapper inventory;

  public InventoryBalanceService(InventoryMapper inventory) {
    this.inventory = inventory;
  }

  @Transactional(propagation = Propagation.NESTED)
  public void reserve(Long orderId, List<InventoryCommandV1.Item> items) {
    for (InventoryCommandV1.Item item : items) {
      if (inventory.reserve(item.productId(), item.quantity()) != 1) {
        throw new InventoryReservationRejectedException(item.productId());
      }
      inventory.insertReservation(orderId, item.productId(), item.quantity());
      movement("reserve:" + UUID.randomUUID(), orderId, item.productId(), "RESERVED",
        item.quantity(), "Stock reserved during checkout");
    }
  }

  public Map<Long, Integer> activeReservations(Long orderId) {
    return inventory.activeReservations(orderId).stream()
      .collect(Collectors.toMap(InventoryReservation::productId, InventoryReservation::quantity));
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
      } else if ("RELEASED".equals(reservation.status())) {
        requireChanged(inventory.commitReleased(reservation.productId(), reservation.quantity()),
          "Stock is unavailable after a late successful payment");
        movementType = "LATE_PAYMENT_COMMITTED";
      } else {
        throw new InventoryBusinessRuleException("Cannot commit reservation in state "
          + reservation.status());
      }
      requireChanged(inventory.markCommitted(reservation.id(), reservation.status()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":commit:" + reservation.id(), orderId, reservation.productId(),
        movementType, reservation.quantity(), "Payment completed the inventory reservation");
    }
  }

  public void release(Long orderId, String eventId, String reason) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"RESERVED".equals(reservation.status())) {
        continue;
      }
      requireChanged(inventory.release(reservation.productId(), reservation.quantity()),
        "Reserved stock is inconsistent");
      requireChanged(inventory.markReleased(reservation.id()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":release:" + reservation.id(), orderId, reservation.productId(),
        "RELEASED", reservation.quantity(), reason);
    }
  }

  public void refund(Long orderId, String eventId) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"COMMITTED".equals(reservation.status())) {
        continue;
      }
      requireChanged(inventory.refund(reservation.productId(), reservation.quantity()),
        "Sold stock is inconsistent");
      requireChanged(inventory.markRefunded(reservation.id()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":refund:" + reservation.id(), orderId, reservation.productId(),
        "REFUNDED", reservation.quantity(), "Successful refund returned sold stock");
    }
  }

  private void movement(String eventId, Long orderId, Long productId, String type,
                        int quantity, String detail) {
    ProductInventory balance = inventory.inventory(productId);
    if (balance == null) {
      throw new IllegalStateException("Product inventory not found");
    }
    inventory.insertMovement(eventId, orderId, productId, type, quantity, balance, detail);
  }

  private void requireChanged(int changed, String message) {
    if (changed != 1) {
      throw new IllegalStateException(message);
    }
  }
}

package vip.fairy.commerceflow.commerce.inventory.application;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.inventory.domain.ProductInventory;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandPublisher;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;

import java.util.List;
import java.util.UUID;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

  private final InventoryMapper inventory;
  private final CustomerNotificationCommandPublisher notifications;

  public InventoryService(InventoryMapper inventory, CustomerNotificationCommandPublisher notifications) {
    this.inventory = inventory;
    this.notifications = notifications;
  }

  @Transactional
  public void reserve(Long orderId, List<CheckoutCartItem> items) {
    for (CheckoutCartItem item : items) {
      if (inventory.reserve(item.getProductId(), item.getQuantity()) != 1) {
        throw new BusinessRuleException("Insufficient stock for " + item.getProductName());
      }
      inventory.insertReservation(orderId, item.getProductId(), item.getQuantity());
      movement("reserve:" + UUID.randomUUID(), orderId, item.getProductId(), "RESERVED",
        item.getQuantity(), "Stock reserved during checkout");
    }
  }

  @Transactional
  public void commit(Long orderId, String eventId) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if ("COMMITTED".equals(reservation.getStatus())) continue;
      String movementType;
      if ("RESERVED".equals(reservation.getStatus())) {
        requireChanged(inventory.commitReserved(reservation.getProductId(), reservation.getQuantity()),
          "Reserved stock is inconsistent");
        movementType = "COMMITTED";
      } else if ("RELEASED".equals(reservation.getStatus())) {
        requireChanged(inventory.commitReleased(reservation.getProductId(), reservation.getQuantity()),
          "Stock is unavailable after a late successful payment");
        movementType = "LATE_PAYMENT_COMMITTED";
      } else {
        continue;
      }
      requireChanged(inventory.markCommitted(reservation.getId(), reservation.getStatus()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":commit:" + reservation.getId(), orderId, reservation.getProductId(),
        movementType, reservation.getQuantity(), "Payment completed the inventory reservation");
    }
  }

  @Transactional
  public void releaseCancelledOrder(Long orderId, String eventId, String reason) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"RESERVED".equals(reservation.getStatus())) continue;
      requireChanged(inventory.release(reservation.getProductId(), reservation.getQuantity()),
        "Reserved stock is inconsistent");
      requireChanged(inventory.markReleased(reservation.getId()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":release:" + reservation.getId(), orderId, reservation.getProductId(),
        "RELEASED", reservation.getQuantity(), reason);
    }
    Long customerId = inventory.customerId(orderId);
    if (customerId != null) {
      notifications.publish(CustomerNotificationCommandV1.orderCancelled(
        eventId + ":notification",
        customerId,
        orderId,
        reason,
        eventId
      ));
    }
  }

  @Transactional
  public void refund(Long orderId, String eventId) {
    for (InventoryReservation reservation : inventory.lockByOrder(orderId)) {
      if (!"COMMITTED".equals(reservation.getStatus())) continue;
      requireChanged(inventory.refund(reservation.getProductId(), reservation.getQuantity()),
        "Sold stock is inconsistent");
      requireChanged(inventory.markRefunded(reservation.getId()),
        "Inventory reservation changed concurrently");
      movement(eventId + ":refund:" + reservation.getId(), orderId, reservation.getProductId(),
        "REFUNDED", reservation.getQuantity(), "Successful refund returned sold stock");
    }
  }

  private void movement(String eventId, Long orderId, Long productId, String type,
                        int quantity, String detail) {
    ProductInventory balance = inventory.inventory(productId);
    if (balance == null) throw new IllegalStateException("Product inventory not found");
    inventory.insertMovement(eventId, orderId, productId, type, quantity, balance, detail);
  }

  private void requireChanged(int changed, String message) {
    if (changed != 1) throw new IllegalStateException(message);
  }
}

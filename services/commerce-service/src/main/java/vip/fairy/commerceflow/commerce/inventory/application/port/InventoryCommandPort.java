package vip.fairy.commerceflow.commerce.inventory.application.port;

import java.util.List;

public interface InventoryCommandPort {

  void reserve(Long orderId, List<ReservationItem> items);

  void commit(Long orderId, String eventId);

  void releaseCancelledOrder(Long orderId, String eventId, String reason);

  void refund(Long orderId, String eventId);

  record ReservationItem(Long productId, String productName, int quantity) {

    public ReservationItem {
      if (productId == null) {
        throw new IllegalArgumentException("Inventory reservation requires productId");
      }
      if (productName == null || productName.isBlank()) {
        throw new IllegalArgumentException("Inventory reservation requires productName");
      }
      if (quantity <= 0) {
        throw new IllegalArgumentException("Inventory reservation quantity must be positive");
      }
    }
  }
}

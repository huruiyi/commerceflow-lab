package vip.fairy.commerceflow.commerce.inventory.application.port;

import java.util.List;

public interface InventoryReservationQuery {

  List<Allocation> activeAllocations(Long orderId);

  record Allocation(long id, long productId, int quantity, String status, long warehouseId) {
  }
}

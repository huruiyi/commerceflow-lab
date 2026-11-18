package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DeliveredInventoryOrderV1 {

  Snapshot requireDelivered(long orderId);

  void markReconciled(long orderId);

  record Snapshot(String orderNumber, String status, Instant createdAt, Instant deliveredAt,
                  List<Product> products, Map<Long, Integer> quantities) {
  }

  record Product(long productId, String name, int quantity) {
  }
}

package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;
import java.util.Map;

public interface DeliveredInventoryOrderV1 {

  Snapshot requireDelivered(long orderId);

  void markReconciled(long orderId);

  record Snapshot(Instant createdAt, Map<Long, Integer> quantities) {
  }
}

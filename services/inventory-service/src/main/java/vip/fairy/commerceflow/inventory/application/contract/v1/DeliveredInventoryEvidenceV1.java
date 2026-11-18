package vip.fairy.commerceflow.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;

public record DeliveredInventoryEvidenceV1(long orderId, String actor, List<Line> lines) {

  public record Line(long productId, int quantity, String warehouseReference, Instant evidenceAt,
                     String note, Integer expectedAvailable, Long bookedMovementId) {
  }
}

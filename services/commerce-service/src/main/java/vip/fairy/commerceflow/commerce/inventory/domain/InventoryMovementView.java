package vip.fairy.commerceflow.commerce.inventory.domain;

import java.time.Instant;

public record InventoryMovementView(
  Long id,
  Long orderId,
  Long productId,
  String movementType,
  int quantity,
  int availableAfter,
  int reservedAfter,
  int soldAfter,
  String detail,
  Instant createdAt
) {
}

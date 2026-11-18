package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;

public record StocktakeV1(String reference, long productId, Baseline baseline, int countedAvailable,
                          Instant countedAt, String note, String countedBy, String approvedBy,
                          List<Long> orderIds) {

  public record Baseline(long productId, int available, int reserved, int sold, long movementId, Instant movementAt) {
  }

  public record Receipt(String reference, Long movementId, int adjustment) {
  }
}

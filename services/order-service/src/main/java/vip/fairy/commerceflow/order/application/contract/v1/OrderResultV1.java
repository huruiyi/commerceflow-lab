package vip.fairy.commerceflow.order.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record OrderResultV1(int schemaVersion, String resultId, OrderResultTypeV1 resultType,
                            OrderResultOutcomeV1 outcome, Instant occurredAt, String producer,
                            Long orderId, String externalReference, String sourceStatus,
                            String errorCode, String message, String correlationId,
                            String causationId, String traceparent) {

  public static final int VERSION = 1;

  public void validate() {
    try {
      UUID.fromString(resultId);
      require(schemaVersion == VERSION && resultType != null && outcome != null
        && occurredAt != null && !blank(producer) && orderId != null && orderId > 0
        && !blank(externalReference) && !blank(sourceStatus) && !blank(message)
        && !blank(correlationId) && !blank(causationId) && !blank(traceparent)
        && OrderCommandV1.TRACEPARENT.matcher(traceparent).matches());
      if (outcome != OrderResultOutcomeV1.SUCCEEDED) require(!blank(errorCode));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Order result V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("Order result V1 is missing required fields");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

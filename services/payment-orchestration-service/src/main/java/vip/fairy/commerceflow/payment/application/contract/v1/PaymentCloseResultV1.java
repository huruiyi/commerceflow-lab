package vip.fairy.commerceflow.payment.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record PaymentCloseResultV1(
  int schemaVersion,
  String resultId,
  PaymentCloseOutcomeV1 outcome,
  Instant occurredAt,
  String producer,
  Long orderId,
  int closedPayments,
  String message,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;

  public void validate() {
    try {
      UUID.fromString(resultId);
      require(schemaVersion == VERSION && outcome != null && occurredAt != null
        && !blank(producer) && orderId != null && orderId > 0 && closedPayments >= 0
        && !blank(message) && !blank(correlationId) && !blank(causationId)
        && !blank(traceparent) && PaymentCreateCommandV1.TRACEPARENT.matcher(traceparent).matches());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Payment close result V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Payment close result V1 is missing required fields");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

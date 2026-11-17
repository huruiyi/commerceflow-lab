package vip.fairy.commerceflow.payment.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record PaymentCloseCommandV1(
  int schemaVersion,
  String commandId,
  Instant occurredAt,
  String producer,
  Long orderId,
  String reason,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;

  public void validate() {
    try {
      UUID.fromString(commandId);
      require(schemaVersion == VERSION && occurredAt != null && !blank(producer)
        && orderId != null && orderId > 0 && !blank(reason) && reason.length() <= 300
        && !blank(correlationId) && !blank(causationId) && !blank(traceparent)
        && PaymentCreateCommandV1.TRACEPARENT.matcher(traceparent).matches());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Payment close command V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Payment close command V1 is missing required fields");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

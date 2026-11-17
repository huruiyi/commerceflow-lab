package vip.fairy.commerceflow.payment.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record PaymentResultV1(
  int schemaVersion,
  String resultId,
  PaymentResultTypeV1 resultType,
  PaymentResultOutcomeV1 outcome,
  Instant occurredAt,
  String producer,
  Long orderId,
  String paymentNumber,
  String refundNumber,
  String sourceStatus,
  String errorCode,
  String message,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;

  public void validate() {
    try {
      UUID.fromString(resultId);
      require(schemaVersion == VERSION && resultType != null && outcome != null
        && occurredAt != null && !blank(producer) && orderId != null && orderId > 0
        && !blank(paymentNumber) && !blank(sourceStatus) && !blank(message)
        && !blank(correlationId) && !blank(causationId) && !blank(traceparent)
        && PaymentCreateCommandV1.TRACEPARENT.matcher(traceparent).matches());
      if (resultType == PaymentResultTypeV1.REFUND_SUCCEEDED
        || resultType == PaymentResultTypeV1.REFUND_FAILED) {
        require(!blank(refundNumber));
      }
      if (outcome != PaymentResultOutcomeV1.SUCCEEDED) {
        require(!blank(errorCode));
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Payment result V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Payment result V1 is missing required fields");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

package vip.fairy.commerceflow.payment.application.contract.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record PaymentCreateCommandV1(
  int schemaVersion,
  String commandId,
  Instant occurredAt,
  String producer,
  Long customerId,
  Long orderId,
  String orderNumber,
  PaymentChannelV1 channel,
  BigDecimal amount,
  String currency,
  Instant paymentDueAt,
  String idempotencyKey,
  boolean simulateFailure,
  String correlationId,
  String causationId,
  String traceparent,
  @JsonInclude(JsonInclude.Include.NON_NULL) Long orderMerchantId
) {

  public static final int VERSION = 1;
  public static final Pattern TRACEPARENT = Pattern.compile(
    "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

  public void validate() {
    try {
      UUID.fromString(commandId);
      require(schemaVersion == VERSION && occurredAt != null && !blank(producer)
        && customerId != null && customerId > 0 && orderId != null && orderId > 0
        && !blank(orderNumber) && channel != null && amount != null && amount.signum() > 0
        && !blank(currency) && paymentDueAt != null && !blank(idempotencyKey)
        && idempotencyKey.length() <= 80 && !blank(correlationId) && !blank(causationId)
        && !blank(traceparent) && TRACEPARENT.matcher(traceparent).matches()
        && (orderMerchantId == null || orderMerchantId > 0));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Payment create command V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Payment create command V1 is missing required fields");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

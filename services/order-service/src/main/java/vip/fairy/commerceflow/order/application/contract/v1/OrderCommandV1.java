package vip.fairy.commerceflow.order.application.contract.v1;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record OrderCommandV1(int schemaVersion, String commandId, OrderCommandTypeV1 commandType,
                             Instant occurredAt, String producer, Long orderId, Long actorId,
                             String expectedStatus, String reason, String carrier,
                             String trackingNumber, String correlationId, String causationId,
                             String traceparent) {

  public static final int VERSION = 1;
  public static final Pattern TRACEPARENT = Pattern.compile(
    "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

  public void validate() {
    try {
      UUID.fromString(commandId);
      require(schemaVersion == VERSION && commandType != null && occurredAt != null
        && !blank(producer) && orderId != null && orderId > 0 && actorId != null && actorId > 0
        && !blank(expectedStatus) && !blank(correlationId) && !blank(causationId)
        && !blank(traceparent) && TRACEPARENT.matcher(traceparent).matches());
      switch (commandType) {
        case CANCEL -> require("PENDING_PAYMENT".equals(expectedStatus) && !blank(reason));
        case CONFIRM_FULFILLMENT -> require("PAID".equals(expectedStatus));
        case SHIP -> require("CONFIRMED".equals(expectedStatus)
          && !blank(carrier) && !blank(trackingNumber));
        case CONFIRM_RECEIPT -> require("SHIPPED".equals(expectedStatus));
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Order command V1 is invalid", exception);
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("Order command V1 is missing required fields");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

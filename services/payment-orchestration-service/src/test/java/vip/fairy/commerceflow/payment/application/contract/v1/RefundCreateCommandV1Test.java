package vip.fairy.commerceflow.payment.application.contract.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RefundCreateCommandV1Test {

  private static final String TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

  @Test
  void validatesRequiredIdentityIdempotencyAndTraceFields() {
    RefundCreateCommandV1 valid = new RefundCreateCommandV1(1, "refund-command", 11L, 7L,
      "refund-key", "CUSTOMER_REQUEST", "Changed mind", "refund-11", "payment-result", TRACE);
    assertDoesNotThrow(valid::validate);
    assertThrows(IllegalArgumentException.class, () -> new RefundCreateCommandV1(1,
      "refund-command", 11L, 7L, "refund-key", "CUSTOMER_REQUEST", "Changed mind",
      "refund-11", "payment-result", "invalid").validate());
  }
}

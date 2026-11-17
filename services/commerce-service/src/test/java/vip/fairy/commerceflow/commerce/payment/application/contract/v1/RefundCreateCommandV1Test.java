package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RefundCreateCommandV1Test {

  private static final String TRACE =
    "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

  @Test
  void validatesStableRefundEnvelope() {
    RefundCreateCommandV1 command = new RefundCreateCommandV1(1, "refund-command", 41L, 7L,
      "refund-key", "CUSTOMER_CANCELLED", "Customer request", "correlation", "causation",
      TRACE);

    assertDoesNotThrow(command::validate);
    assertThrows(IllegalArgumentException.class, () -> new RefundCreateCommandV1(2,
      "refund-command", 41L, 7L, "refund-key", "CUSTOMER_CANCELLED", "Customer request",
      "correlation", "causation", TRACE).validate());
  }
}

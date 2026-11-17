package vip.fairy.commerceflow.order.application.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderContractV1Test {

  @Test
  void acceptsTheFrozenCommandAndResultContract() {
    String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
    var command = new OrderCommandV1(1, UUID.randomUUID().toString(), OrderCommandTypeV1.CANCEL,
      Instant.parse("2026-11-17T00:00:00Z"), "commerce-service", 42L, 7L,
      "PENDING_PAYMENT", "customer request", null, null, "correlation", "causation", traceparent);
    var result = new OrderResultV1(1, UUID.randomUUID().toString(), OrderResultTypeV1.PAYMENT,
      OrderResultOutcomeV1.SUCCEEDED, Instant.parse("2026-11-17T00:00:01Z"), "payment-service",
      42L, "payment-42", "SUCCEEDED", null, "paid", "correlation", "causation", traceparent);

    assertThat(OrderContractSemantics.fingerprint(command)).hasSize(64);
    assertThat(OrderContractSemantics.fingerprint(result)).hasSize(64);
  }

  @Test
  void rejectsAnInvalidTraceContext() {
    var command = new OrderCommandV1(1, UUID.randomUUID().toString(), OrderCommandTypeV1.CANCEL,
      Instant.now(), "commerce-service", 42L, 7L, "PENDING_PAYMENT", "reason", null, null,
      "correlation", "causation", "not-a-traceparent");

    assertThatThrownBy(command::validate).isInstanceOf(IllegalArgumentException.class);
  }
}

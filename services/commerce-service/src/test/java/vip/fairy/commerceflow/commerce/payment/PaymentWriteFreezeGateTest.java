package vip.fairy.commerceflow.commerce.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import vip.fairy.commerceflow.commerce.payment.application.PaymentWriteFreezeGate;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

class PaymentWriteFreezeGateTest {

  @Test
  void allowsWritesOutsideTheCutoverWindow() {
    assertThatCode(() -> new PaymentWriteFreezeGate(false).requireWritable())
      .doesNotThrowAnyException();
  }

  @Test
  void rejectsWritesDuringFinalCatchUp() {
    assertThatThrownBy(() -> new PaymentWriteFreezeGate(true).requireWritable())
      .isInstanceOf(ExternalServiceUnavailableException.class)
      .hasMessageContaining("frozen");
  }
}

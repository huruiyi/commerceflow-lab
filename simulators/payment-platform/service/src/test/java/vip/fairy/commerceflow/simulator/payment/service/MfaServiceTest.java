package vip.fairy.commerceflow.simulator.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MfaServiceTest {

  @Test
  void implementsRfc6238CompatibleSixDigitTotp() {
    assertThat(MfaService.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1)).isEqualTo("287082");
  }
}

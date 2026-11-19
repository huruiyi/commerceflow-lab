package vip.fairy.commerceflow.payment.application.contract.v1;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentContractV1Test {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void frozenCommerceFixturesRemainCompatible() throws Exception {
    assertEquals("UNION_PAY", json.readValue("\"UNION_PAY\"", PaymentChannelV1.class).name());
    try (InputStream createFixture = getClass().getResourceAsStream(
      "/contracts/payment/payment-create-command-v1.json");
         InputStream closeFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-close-command-v1.json");
         InputStream closeResultFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-close-result-v1.json");
         InputStream resultFixture = getClass().getResourceAsStream(
           "/contracts/payment/payment-success-result-v1.json")) {
      assertNotNull(createFixture);
      assertNotNull(closeFixture);
      assertNotNull(closeResultFixture);
      assertNotNull(resultFixture);
      PaymentCreateCommandV1 create = json.readValue(createFixture, PaymentCreateCommandV1.class);
      PaymentCloseCommandV1 close = json.readValue(closeFixture, PaymentCloseCommandV1.class);
      PaymentCloseResultV1 closeResult = json.readValue(closeResultFixture,
        PaymentCloseResultV1.class);
      PaymentResultV1 result = json.readValue(resultFixture, PaymentResultV1.class);
      create.validate();
      close.validate();
      closeResult.validate();
      result.validate();
      new PaymentQueryV1(1, create.orderId()).validate();
      assertEquals(64, PaymentContractSemantics.fingerprint(create).length());
      assertEquals(64, PaymentContractSemantics.fingerprint(close).length());
      assertEquals(64, PaymentContractSemantics.fingerprint(result).length());
    }
  }
}

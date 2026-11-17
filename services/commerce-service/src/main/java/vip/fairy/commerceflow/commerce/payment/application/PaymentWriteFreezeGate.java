package vip.fairy.commerceflow.commerce.payment.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

@Component
public class PaymentWriteFreezeGate implements PaymentWriteAdmissionV1 {

  private final boolean frozen;

  public PaymentWriteFreezeGate(
    @Value("${commerceflow.payment.cutover.write-frozen:false}") boolean frozen
  ) {
    this.frozen = frozen;
  }

  @Override
  public void requireWritable() {
    if (frozen) {
      throw new ExternalServiceUnavailableException("PAYMENT_WRITE_FROZEN",
        "Payment writes are frozen for final catch-up", null);
    }
  }
}

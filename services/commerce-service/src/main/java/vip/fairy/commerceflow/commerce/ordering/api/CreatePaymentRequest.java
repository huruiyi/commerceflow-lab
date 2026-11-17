package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;

public record CreatePaymentRequest(
  @NotBlank @Size(max = 80) String idempotencyKey,
  boolean simulateFailure,
  PaymentChannelV1 channel
) {

  public CreatePaymentRequest {
    if (channel == null) {
      channel = PaymentChannelV1.ALIPAY;
    }
  }
}

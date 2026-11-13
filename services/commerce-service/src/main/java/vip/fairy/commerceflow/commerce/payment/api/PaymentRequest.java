package vip.fairy.commerceflow.commerce.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;

public record PaymentRequest(
  @NotBlank @Size(max = 80) String idempotencyKey,
  boolean simulateFailure,
  PaymentChannel channel) {
  public PaymentRequest {
    if (channel == null) {
      channel = PaymentChannel.ALIPAY;
    }
  }
}

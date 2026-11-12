package vip.fairy.commerceflow.commerce.payment.api;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentChannel;
import jakarta.validation.constraints.*;

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

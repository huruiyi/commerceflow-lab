package lab.commerceflow.storefront.web.dto;

import lab.commerceflow.storefront.domain.PaymentChannel;
import jakarta.validation.constraints.*;

public record PaymentRequest(
    @NotBlank @Size(max=80) String idempotencyKey,
    boolean simulateFailure,
    PaymentChannel channel) {
  public PaymentRequest {
    if (channel == null) {
      channel = PaymentChannel.ALIPAY;
    }
  }
}

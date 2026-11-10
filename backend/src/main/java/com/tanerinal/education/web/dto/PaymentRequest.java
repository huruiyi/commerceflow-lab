package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.PaymentChannel;
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

package vip.fairy.commerceflow.simulator.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMerchantRequest(
  @NotBlank @Size(max = 100) String displayName,
  @Size(max = 500) String notifyUrl,
  @Size(max = 32) String signingAlgorithm,
  @Size(max = 8000) String merchantPublicKey) {
}

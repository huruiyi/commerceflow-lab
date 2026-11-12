package vip.fairy.commerceflow.simulator.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MerchantAccessRequest(@NotNull Long merchantId, @NotBlank String permissionScope) {
}

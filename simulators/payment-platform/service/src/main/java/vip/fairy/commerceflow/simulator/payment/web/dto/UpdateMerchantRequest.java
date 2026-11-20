package vip.fairy.commerceflow.simulator.payment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMerchantRequest(@NotBlank @Size(max = 100) String displayName,
                                    @Size(max = 1000) String remark) {
}

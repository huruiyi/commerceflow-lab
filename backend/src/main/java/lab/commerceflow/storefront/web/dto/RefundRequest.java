package lab.commerceflow.storefront.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RefundRequest(
    @NotBlank @Size(max=80) String idempotencyKey,
    @NotBlank @Pattern(regexp="CUSTOMER_CANCELLED|ORDER_ERROR|OTHER") String reasonCode,
    @Size(max=500) String reasonDetail) {
}

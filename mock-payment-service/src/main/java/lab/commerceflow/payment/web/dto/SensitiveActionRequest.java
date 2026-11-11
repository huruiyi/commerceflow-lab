package lab.commerceflow.payment.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SensitiveActionRequest(@NotBlank String password) {
}

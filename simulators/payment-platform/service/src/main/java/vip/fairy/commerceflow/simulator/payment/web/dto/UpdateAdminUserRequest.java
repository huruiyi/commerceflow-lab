package vip.fairy.commerceflow.simulator.payment.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAdminUserRequest(
  @NotBlank @Size(max = 100) String displayName,
  @NotBlank @Email @Size(max = 128) String email) {
}

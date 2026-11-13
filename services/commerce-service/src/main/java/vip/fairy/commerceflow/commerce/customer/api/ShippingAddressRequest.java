package vip.fairy.commerceflow.commerce.customer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShippingAddressRequest(
  @NotBlank @Size(max = 100) String recipientName,
  @NotBlank @Size(max = 40) String phone,
  @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
  @NotBlank @Size(max = 100) String stateProvince,
  @NotBlank @Size(max = 100) String city,
  @NotBlank @Size(max = 100) String district,
  @NotBlank @Size(max = 300) String addressLine1,
  @Size(max = 300) String addressLine2,
  @NotBlank @Size(max = 20) String postalCode,
  @NotBlank @Size(max = 40) String label,
  boolean isDefault) {
}

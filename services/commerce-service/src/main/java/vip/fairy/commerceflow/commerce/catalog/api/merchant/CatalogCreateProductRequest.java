package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CatalogCreateProductRequest(
  @NotNull UUID requestId,
  @NotBlank @Size(max = 160) String name,
  @NotBlank @Size(max = 2000) String description,
  @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
  @NotBlank @Size(max = 500) String imageUrl
) {
}

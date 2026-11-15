package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CatalogPriceUpdateRequest(
  @Positive long expectedVersion,
  @NotNull @DecimalMin(value = "0.01") BigDecimal unitPrice
) {
}

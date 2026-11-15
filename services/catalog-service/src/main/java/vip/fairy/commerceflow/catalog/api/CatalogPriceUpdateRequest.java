package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CatalogPriceUpdateRequest(@Positive long expectedVersion,
                                        @NotNull @DecimalMin("0.01") BigDecimal unitPrice) {
}

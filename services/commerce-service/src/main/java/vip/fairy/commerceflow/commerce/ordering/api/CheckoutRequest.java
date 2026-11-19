package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutRequest(@Positive Long addressId, @DecimalMin("0.00") BigDecimal expectedTotal,
                              @Size(min = 1) List<@NotNull @Positive Long> itemIds) {
}

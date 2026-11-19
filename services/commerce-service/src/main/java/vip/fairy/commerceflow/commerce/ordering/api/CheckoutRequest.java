package vip.fairy.commerceflow.commerce.ordering.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CheckoutRequest(@NotNull @Pattern(regexp = "[0-9a-f]{64}") String quoteId,
                              @NotNull @Valid Selection selection) {

  public record Selection(@Positive Long addressId, @Size(min = 1) List<@NotNull @Positive Long> itemIds,
                          UUID couponId) {
  }
}

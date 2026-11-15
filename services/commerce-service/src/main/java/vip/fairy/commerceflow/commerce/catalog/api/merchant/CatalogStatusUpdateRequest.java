package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductLifecycleStatus;

public record CatalogStatusUpdateRequest(
  @Positive long expectedVersion,
  @NotNull ProductLifecycleStatus status
) {
}

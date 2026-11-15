package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import vip.fairy.commerceflow.catalog.domain.ProductLifecycleStatus;

public record CatalogStatusUpdateRequest(@Positive long expectedVersion,
                                         @NotNull ProductLifecycleStatus status) {
}

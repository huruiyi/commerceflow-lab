package vip.fairy.commerceflow.commerce.catalog.api.v1;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CatalogSnapshotBatchRequest(
  @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> productIds
) {
}

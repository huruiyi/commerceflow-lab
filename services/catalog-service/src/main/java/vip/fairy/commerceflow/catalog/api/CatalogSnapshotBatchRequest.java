package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CatalogSnapshotBatchRequest(@NotEmpty @Size(max = 100) List<Long> productIds) {
}

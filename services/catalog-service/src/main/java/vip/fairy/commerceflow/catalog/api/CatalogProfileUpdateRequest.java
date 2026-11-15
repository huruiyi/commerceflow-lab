package vip.fairy.commerceflow.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CatalogProfileUpdateRequest(@Positive long expectedVersion,
                                          @NotBlank @Size(max = 160) String name,
                                          @NotBlank @Size(max = 300) String brief,
                                          @NotBlank @Size(max = 2000) String description,
                                          @NotBlank @Size(max = 500) String imageUrl,
                                          @NotBlank @Size(max = 500) String thumbnailUrl) {
}

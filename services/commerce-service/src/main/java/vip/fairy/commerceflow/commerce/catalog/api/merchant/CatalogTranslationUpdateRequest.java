package vip.fairy.commerceflow.commerce.catalog.api.merchant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CatalogTranslationUpdateRequest(
  @Positive long expectedVersion,
  @NotBlank @Size(max = 160) String name,
  @NotBlank @Size(max = 300) String brief,
  @NotBlank @Size(max = 2000) String description
) {
}

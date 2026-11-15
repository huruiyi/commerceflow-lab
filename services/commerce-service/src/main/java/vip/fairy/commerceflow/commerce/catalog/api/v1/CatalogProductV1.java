package vip.fairy.commerceflow.commerce.catalog.api.v1;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;

import java.math.BigDecimal;
import java.time.Instant;

public record CatalogProductV1(int schemaVersion, Long productId, Long merchantId,
                               String name, String brief, String description,
                               BigDecimal unitPrice, String imageUrl, String thumbnailUrl,
                               String lifecycleStatus, long businessVersion, Instant updatedAt) {

  public static final int VERSION = 1;

  public static CatalogProductV1 from(Product product, ProductTranslation translation) {
    return new CatalogProductV1(
      VERSION,
      product.getId(),
      product.getMerchantId(),
      translation == null ? product.getName() : translation.getName(),
      translation == null ? product.getBrief() : translation.getBrief(),
      translation == null ? product.getDescription() : translation.getDescription(),
      product.getPrice(),
      product.getImageUrl(),
      product.getThumbnailUrl(),
      product.getLifecycleStatus(),
      product.getBusinessVersion(),
      product.getUpdatedAt());
  }
}

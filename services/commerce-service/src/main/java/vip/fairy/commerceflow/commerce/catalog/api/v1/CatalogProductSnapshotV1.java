package vip.fairy.commerceflow.commerce.catalog.api.v1;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;

import java.math.BigDecimal;

public record CatalogProductSnapshotV1(int schemaVersion, Long productId, Long merchantId,
                                       String name, BigDecimal unitPrice, String thumbnailUrl,
                                       String lifecycleStatus, long businessVersion) {

  public static CatalogProductSnapshotV1 from(Product product) {
    return new CatalogProductSnapshotV1(
      CatalogProductV1.VERSION,
      product.getId(),
      product.getMerchantId(),
      product.getName(),
      product.getPrice(),
      product.getThumbnailUrl(),
      product.getLifecycleStatus(),
      product.getBusinessVersion());
  }
}

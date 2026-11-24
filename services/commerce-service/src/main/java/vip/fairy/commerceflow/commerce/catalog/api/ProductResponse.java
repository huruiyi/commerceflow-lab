package vip.fairy.commerceflow.commerce.catalog.api;

import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogMonitorProfile;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogSkuV1;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl,
                              BigDecimal averageRating, int reviewCount, List<CatalogReviewResponse> reviews,
                              Long merchantId, List<CatalogSkuV1> skus, BigDecimal minimumSkuPrice,
                              BigDecimal maximumSkuPrice, CatalogMonitorProfile monitorProfile) {
  public static ProductResponse from(CatalogProductV1 product,
                                     BigDecimal averageRating,
                                     int reviewCount,
                                     List<CatalogReviewResponse> reviews) {
    return new ProductResponse(
      product.productId(), product.name(), product.brief(), product.description(),
      product.unitPrice(), product.imageUrl(), product.thumbnailUrl(),
      averageRating,
      reviewCount,
      reviews, product.merchantId(), List.of(), null, null, product.monitorProfile());
  }

  public ProductResponse withSkus(List<CatalogSkuV1> variants) {
    return new ProductResponse(id, name, brief, description, price, imageUrl, thumbnailUrl,
      averageRating, reviewCount, reviews, merchantId, variants == null ? List.of() : variants,
      null, null, monitorProfile);
  }

  public ProductResponse withSkuRange(BigDecimal min, BigDecimal max) {
    return new ProductResponse(id, name, brief, description, price, imageUrl, thumbnailUrl,
      averageRating, reviewCount, reviews, merchantId, skus, min, max, monitorProfile);
  }
}

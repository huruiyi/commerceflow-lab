package vip.fairy.commerceflow.commerce.catalog.api;

import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl,
                              BigDecimal averageRating, int reviewCount, List<CatalogReviewResponse> reviews) {
  public static ProductResponse from(CatalogProductV1 product,
                                     BigDecimal averageRating,
                                     int reviewCount,
                                     List<CatalogReviewResponse> reviews) {
    return new ProductResponse(
      product.productId(), product.name(), product.brief(), product.description(),
      product.unitPrice(), product.imageUrl(), product.thumbnailUrl(),
      averageRating,
      reviewCount,
      reviews);
  }
}

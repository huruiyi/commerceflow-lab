package vip.fairy.commerceflow.commerce.catalog.api;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductTranslation;
import vip.fairy.commerceflow.commerce.review.api.ProductReviewResponse;

import java.math.BigDecimal;
import java.util.List;

public record ProductResponse(Long id, String name, String brief, String description,
                              BigDecimal price, String imageUrl, String thumbnailUrl,
                              BigDecimal averageRating, int reviewCount, List<ProductReviewResponse> reviews) {
  public static ProductResponse from(Product product,
                                     ProductTranslation translation,
                                     BigDecimal averageRating,
                                     int reviewCount,
                                     List<ProductReviewResponse> reviews) {
    return new ProductResponse(
      product.getId(),
      translation == null ? product.getName() : translation.getName(),
      translation == null ? product.getBrief() : translation.getBrief(),
      translation == null ? product.getDescription() : translation.getDescription(),
      product.getPrice(),
      product.getImageUrl(),
      product.getThumbnailUrl(),
      averageRating,
      reviewCount,
      reviews);
  }
}

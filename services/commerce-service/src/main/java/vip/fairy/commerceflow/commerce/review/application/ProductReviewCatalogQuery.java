package vip.fairy.commerceflow.commerce.review.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ProductReviewCatalogQuery {

  View find(Long productId);
  List<Long> ratedProductIds();

  record View(BigDecimal averageRating, int reviewCount, List<Item> reviews) {
  }

  record Item(Long id, int rating, String comment, String reviewerName, Instant createdAt) {
  }
}

package vip.fairy.commerceflow.commerce.review.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ProductReviewCatalogQuery {

  Summary EMPTY = new Summary(BigDecimal.ZERO, 0);

  Map<Long, Summary> summaries(List<Long> productIds);

  List<Item> page(Long productId, long offset, int size);

  List<Long> ratedProductIds();

  record Summary(BigDecimal averageRating, int reviewCount) {
  }

  record Item(Long id, int rating, String comment, String reviewerName, Instant createdAt) {
  }
}

package vip.fairy.commerceflow.commerce.review.infrastructure;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vip.fairy.commerceflow.commerce.review.application.ProductReviewCatalogQuery;

@Component
public class ProductReviewCatalogQueryAdapter implements ProductReviewCatalogQuery {

  private final ReviewMapper reviews;

  public ProductReviewCatalogQueryAdapter(ReviewMapper reviews) {
    this.reviews = reviews;
  }

  @Override
  public Map<Long, Summary> summaries(List<Long> productIds) {
    Map<Long, Summary> summaries = new HashMap<>();
    for (int start = 0; start < productIds.size(); start += 100) {
      var ids = productIds.subList(start, Math.min(start + 100, productIds.size()));
      for (var row : reviews.summaries(ids)) {
        summaries.put(row.productId(), new Summary(row.averageRating(), row.reviewCount()));
      }
    }
    return summaries;
  }

  @Override
  public List<Item> page(Long productId, long offset, int size) {
    return reviews.page(productId, offset, size).stream()
      .map(review -> new Item(review.getId(), review.getRating(), review.getComment(),
        review.getReviewerName(), review.getCreatedAt()))
      .toList();
  }

  @Override
  public List<Long> ratedProductIds() {
    return reviews.ratedProductIds();
  }
}

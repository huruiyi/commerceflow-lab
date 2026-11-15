package vip.fairy.commerceflow.commerce.review.infrastructure;

import org.springframework.stereotype.Component;
import vip.fairy.commerceflow.commerce.review.application.ProductReviewCatalogQuery;

@Component
public class ProductReviewCatalogQueryAdapter implements ProductReviewCatalogQuery {

  private final ReviewMapper reviews;

  public ProductReviewCatalogQueryAdapter(ReviewMapper reviews) {
    this.reviews = reviews;
  }

  @Override
  public View find(Long productId) {
    return new View(
      reviews.average(productId),
      reviews.count(productId),
      reviews.reviews(productId).stream()
        .map(review -> new Item(
          review.getId(),
          review.getRating(),
          review.getComment(),
          review.getReviewerName(),
          review.getCreatedAt()))
        .toList());
  }
}

package vip.fairy.commerceflow.commerce.review.api;

import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;

import java.time.Instant;

public record ProductReviewResponse(Long id, int rating, String comment, String reviewerName, Instant createdAt) {
  public static ProductReviewResponse from(ProductReviewView review) {
    return new ProductReviewResponse(
      review.getId(),
      review.getRating(),
      review.getComment(),
      review.getReviewerName(),
      review.getCreatedAt());
  }
}

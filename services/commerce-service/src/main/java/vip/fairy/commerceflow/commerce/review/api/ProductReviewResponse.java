package vip.fairy.commerceflow.commerce.review.api;

import vip.fairy.commerceflow.commerce.review.domain.ProductReviewView;

import java.time.Instant;

public record ProductReviewResponse(Long id, int rating, String comment, String reviewerName, Instant createdAt) {
  public static ProductReviewResponse from(ProductReviewView v) {
    return new ProductReviewResponse(v.getId(), v.getRating(), v.getComment(), v.getReviewerName(), v.getCreatedAt());
  }
}

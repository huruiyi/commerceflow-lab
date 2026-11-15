package vip.fairy.commerceflow.commerce.catalog.api;

import java.time.Instant;

public record CatalogReviewResponse(Long id, int rating, String comment, String reviewerName, Instant createdAt) {
}

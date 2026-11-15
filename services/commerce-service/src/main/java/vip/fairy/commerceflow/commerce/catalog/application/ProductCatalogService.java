package vip.fairy.commerceflow.commerce.catalog.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.CatalogReviewResponse;
import vip.fairy.commerceflow.commerce.catalog.api.ProductResponse;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;
import vip.fairy.commerceflow.commerce.review.application.ProductReviewCatalogQuery;

import java.util.List;

@Service
public class ProductCatalogService implements CatalogProductLookup, CatalogCacheInvalidator {

  private final CatalogServiceClient catalog;
  private final ProductReviewCatalogQuery reviews;

  public ProductCatalogService(CatalogServiceClient catalog, ProductReviewCatalogQuery reviews) {
    this.catalog = catalog;
    this.reviews = reviews;
  }

  public List<ProductResponse> list(String requestedLocale) {
    return catalog.list(requestedLocale).stream().map(this::response).toList();
  }

  @Override
  public ProductSnapshot require(Long productId) {
    CatalogProductV1 product = catalog.detail(productId, null);
    return new ProductSnapshot(product.productId(), product.merchantId(), product.name(),
      product.unitPrice(), product.thumbnailUrl());
  }

  public ProductResponse detail(Long productId, String requestedLocale) {
    return response(catalog.detail(productId, requestedLocale));
  }

  @Override
  public void invalidate() {
    // catalog-service is the sole owner and commerce-service has no catalog cache.
  }

  private ProductResponse response(CatalogProductV1 product) {
    ProductReviewCatalogQuery.View reviewView = reviews.find(product.productId());
    List<CatalogReviewResponse> productReviews = reviewView.reviews().stream()
      .map(review -> new CatalogReviewResponse(review.id(), review.rating(), review.comment(),
        review.reviewerName(), review.createdAt()))
      .toList();
    return ProductResponse.from(product, reviewView.averageRating(), reviewView.reviewCount(),
      productReviews);
  }
}

package vip.fairy.commerceflow.commerce.catalog.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.CatalogReviewResponse;
import vip.fairy.commerceflow.commerce.catalog.api.ProductResponse;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;
import vip.fairy.commerceflow.commerce.review.application.ProductReviewCatalogQuery;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import java.util.HashMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ProductCatalogService implements CatalogProductLookup, CatalogCacheInvalidator {

  private final CatalogServiceClient catalog;
  private final ProductReviewCatalogQuery reviews;
  private final StoreTradeV1 stores;

  public ProductCatalogService(CatalogServiceClient catalog, ProductReviewCatalogQuery reviews, StoreTradeV1 stores) {
    this.catalog = catalog;
    this.reviews = reviews;
    this.stores = stores;
  }

  public List<ProductResponse> list(String requestedLocale) {
    var active = stores.activeMerchantIds();
    return catalog.list(requestedLocale).stream()
      .filter(product -> active.contains(product.merchantId())).map(this::response).toList();
  }

  public SearchPage search(String query, String locale, String sort, Long merchantId,
                           BigDecimal minPrice, BigDecimal maxPrice, int page, int size) {
    var active = stores.activeMerchantIds();
    List<Long> merchants = merchantId == null ? List.copyOf(active)
      : active.contains(merchantId) ? List.of(merchantId) : List.of();
    var result = catalog.search(new CatalogServiceClient.SearchRequest(query, locale, sort, merchants,
      "rating".equals(sort) ? reviews.ratedProductIds() : List.of(), minPrice, maxPrice, page, size));
    return new SearchPage(result.products().stream().map(this::response).toList(),
      result.total(), result.page(), result.size());
  }

  public record SearchPage(List<ProductResponse> products, long total, int page, int size) {
  }

  public SearchStatus searchStatus(boolean rebuild) {
    return catalog.searchStatus(rebuild);
  }

  public record SearchStatus(long generation, boolean rebuildRequested, Instant rebuiltAt, long pending) {
  }

  @Override
  public ProductSnapshot require(Long productId) {
    CatalogProductV1 product = catalog.detail(productId, null);
    stores.requireActive(product.merchantId(), false);
    return new ProductSnapshot(product.productId(), product.merchantId(), product.name(),
      product.unitPrice(), product.thumbnailUrl());
  }

  @Override
  public Map<Long, ProductSnapshot> snapshots(List<Long> productIds) {
    Map<Long, ProductSnapshot> snapshots = new HashMap<>();
    for (int start = 0; start < productIds.size(); start += 100) {
      int end = Math.min(start + 100, productIds.size());
      for (var product : catalog.snapshots(productIds.subList(start, end))) {
        snapshots.put(product.productId(), new ProductSnapshot(product.productId(), product.merchantId(),
          product.name(), product.unitPrice(), product.thumbnailUrl()));
      }
    }
    return snapshots;
  }

  public ProductResponse detail(Long productId, String requestedLocale) {
    var product = catalog.detail(productId, requestedLocale);
    if (!stores.activeMerchantIds().contains(product.merchantId())) {
      throw new ResourceNotFoundException("Product not found");
    }
    return response(product);
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

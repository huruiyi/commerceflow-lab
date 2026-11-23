package vip.fairy.commerceflow.commerce.catalog.application;

import org.springframework.stereotype.Service;
import vip.fairy.commerceflow.commerce.catalog.api.CatalogReviewResponse;
import vip.fairy.commerceflow.commerce.catalog.api.ProductResponse;
import vip.fairy.commerceflow.commerce.catalog.api.v1.CatalogProductV1;
import vip.fairy.commerceflow.commerce.catalog.infrastructure.client.CatalogServiceClient;
import vip.fairy.commerceflow.commerce.merchant.application.contract.v1.StoreTradeV1;
import vip.fairy.commerceflow.commerce.review.application.ProductReviewCatalogQuery;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductCatalogService implements CatalogProductLookup, CatalogCacheInvalidator {

  private static final int DETAIL_REVIEW_SIZE = 5;

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
    return withRatings(catalog.list(requestedLocale).stream()
      .filter(product -> active.contains(product.merchantId())).toList());
  }

  public SearchPage search(String query, String locale, String sort, Long merchantId,
                           BigDecimal minPrice, BigDecimal maxPrice, int page, int size) {
    var active = stores.activeMerchantIds();
    List<Long> merchants = merchantId == null ? List.copyOf(active)
      : active.contains(merchantId) ? List.of(merchantId) : List.of();
    var result = catalog.search(new CatalogServiceClient.SearchRequest(query, locale, sort, merchants,
      "rating".equals(sort) ? reviews.ratedProductIds() : List.of(), minPrice, maxPrice, page, size));
    return new SearchPage(withRatings(result.products()),
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
    var product = activeProduct(productId, requestedLocale);
    var summary = reviewSummary(productId);
    return ProductResponse.from(product, summary.averageRating(), summary.reviewCount(),
      reviewItems(productId, 0, DETAIL_REVIEW_SIZE, summary.reviewCount()));
  }

  public ReviewPage reviewPage(Long productId, int page, int size) {
    activeProduct(productId, null);
    int total = reviewSummary(productId).reviewCount();
    return new ReviewPage(reviewItems(productId, (long) page * size, size, total), total, page, size);
  }

  public record ReviewPage(List<CatalogReviewResponse> reviews, int total, int page, int size) {
  }

  public Map<Long, Listing> lookup(List<Long> productIds, String locale) {
    if (productIds.isEmpty()) {
      return Map.of();
    }
    var active = stores.activeMerchantIds();
    return catalog.lookup(productIds, locale).stream()
      .filter(product -> active.contains(product.merchantId()))
      .collect(Collectors.toMap(CatalogProductV1::productId,
        product -> new Listing(product.name(), product.unitPrice())));
  }

  public record Listing(String name, BigDecimal price) {
  }

  private CatalogProductV1 activeProduct(Long productId, String requestedLocale) {
    var product = catalog.detail(productId, requestedLocale);
    if (!stores.activeMerchantIds().contains(product.merchantId())) {
      throw new ResourceNotFoundException("Product not found");
    }
    return product;
  }

  @Override
  public void invalidate() {
    // catalog-service is the sole owner and commerce-service has no catalog cache.
  }

  private List<ProductResponse> withRatings(List<CatalogProductV1> products) {
    var summaries = reviews.summaries(products.stream().map(CatalogProductV1::productId).toList());
    return products.stream().map(product -> {
      var summary = summaries.getOrDefault(product.productId(), ProductReviewCatalogQuery.EMPTY);
      return ProductResponse.from(product, summary.averageRating(), summary.reviewCount(), List.of());
    }).toList();
  }

  private ProductReviewCatalogQuery.Summary reviewSummary(Long productId) {
    return reviews.summaries(List.of(productId)).getOrDefault(productId, ProductReviewCatalogQuery.EMPTY);
  }

  private List<CatalogReviewResponse> reviewItems(Long productId, long offset, int size, int total) {
    if (offset >= total) {
      return List.of();
    }
    return reviews.page(productId, offset, size).stream()
      .map(review -> new CatalogReviewResponse(review.id(), review.rating(), review.comment(),
        review.reviewerName(), review.createdAt()))
      .toList();
  }
}

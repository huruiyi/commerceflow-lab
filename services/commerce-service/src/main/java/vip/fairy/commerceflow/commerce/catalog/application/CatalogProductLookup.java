package vip.fairy.commerceflow.commerce.catalog.application;

import java.math.BigDecimal;

public interface CatalogProductLookup {

  ProductSnapshot require(Long productId);

  record ProductSnapshot(Long id, Long merchantId, String name, BigDecimal price, String thumbnailUrl) {
  }
}

package vip.fairy.commerceflow.commerce.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface CatalogProductLookup {

  ProductSnapshot require(Long productId);

  ProductSnapshot requireSku(Long productId, Long skuId);

  Map<Long, ProductSnapshot> snapshots(List<Long> productIds);

  record ProductSnapshot(Long id, Long merchantId, String name, BigDecimal price, String thumbnailUrl) {
  }
}

package vip.fairy.commerceflow.commerce.catalog.api.v1;

import java.math.BigDecimal;
import java.time.Instant;

public record CatalogProductV1(int schemaVersion, Long productId, Long merchantId,
                               String name, String brief, String description,
                               BigDecimal unitPrice, String imageUrl, String thumbnailUrl,
                               String lifecycleStatus, long businessVersion, Instant updatedAt) {

  public static final int VERSION = 1;

}

package vip.fairy.commerceflow.commerce.catalog.api.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CatalogProductV1(int schemaVersion, Long productId, Long merchantId,
                               String name, String brief, String description,
                               BigDecimal unitPrice, String imageUrl, String thumbnailUrl,
                               String lifecycleStatus, long businessVersion, Instant updatedAt,
                               CatalogMonitorProfile monitorProfile) {

  public static final int VERSION = 1;

  public record Page(List<CatalogProductV1> rows, long total, int page, int size) {
  }

  public CatalogProductV1(int schemaVersion, Long productId, Long merchantId,
                          String name, String brief, String description, BigDecimal unitPrice,
                          String imageUrl, String thumbnailUrl, String lifecycleStatus, long businessVersion,
                          Instant updatedAt) {
    this(schemaVersion, productId, merchantId, name, brief, description, unitPrice, imageUrl, thumbnailUrl,
      lifecycleStatus, businessVersion, updatedAt, null);
  }

}

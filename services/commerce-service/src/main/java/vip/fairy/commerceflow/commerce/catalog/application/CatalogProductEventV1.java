package vip.fairy.commerceflow.commerce.catalog.application;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.catalog.domain.ProductLifecycleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * catalog 商品变更事件第一版契约。消费者必须以 eventId 去重，并按 businessVersion 处理乱序与版本缺口。
 */
public record CatalogProductEventV1(
  int schemaVersion,
  String eventId,
  String eventType,
  Instant occurredAt,
  Long productId,
  Long merchantId,
  long businessVersion,
  String name,
  String brief,
  String description,
  BigDecimal price,
  String imageUrl,
  String thumbnailUrl,
  String lifecycleStatus,
  String locale,
  String correlationId,
  String causationId
) {

  public static final int VERSION = 1;

  public static CatalogProductEventV1 from(Product product, CatalogProductEventType type,
                                           String locale, String correlationId,
                                           String causationId) {
    return new CatalogProductEventV1(
      VERSION,
      UUID.randomUUID().toString(),
      type.name(),
      Instant.now(),
      product.getId(),
      product.getMerchantId(),
      product.getBusinessVersion(),
      product.getName(),
      product.getBrief(),
      product.getDescription(),
      product.getPrice(),
      product.getImageUrl(),
      product.getThumbnailUrl(),
      product.getLifecycleStatus(),
      locale,
      normalized(correlationId),
      normalized(causationId));
  }

  public static CatalogProductEventV1 translation(Product product, String locale,
                                                   String name, String brief, String description,
                                                   String correlationId, String causationId) {
    return new CatalogProductEventV1(
      VERSION,
      UUID.randomUUID().toString(),
      CatalogProductEventType.PRODUCT_TRANSLATION_UPDATED.name(),
      Instant.now(),
      product.getId(),
      product.getMerchantId(),
      product.getBusinessVersion(),
      name,
      brief,
      description,
      product.getPrice(),
      product.getImageUrl(),
      product.getThumbnailUrl(),
      product.getLifecycleStatus(),
      locale,
      normalized(correlationId),
      normalized(causationId));
  }

  public void validate() {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported catalog event schema version");
    }
    UUID.fromString(eventId);
    CatalogProductEventType.valueOf(eventType);
    if (occurredAt == null || productId == null || productId <= 0 || merchantId == null || merchantId <= 0
      || businessVersion <= 0 || correlationId == null || correlationId.isBlank()
      || causationId == null || causationId.isBlank()) {
      throw new IllegalArgumentException("Catalog event is missing required identity or version fields");
    }
    if (correlationId.length() > 100 || causationId.length() > 100) {
      throw new IllegalArgumentException("Catalog correlation and causation IDs must not exceed 100 characters");
    }
    if (name == null || name.isBlank() || price == null || price.signum() <= 0) {
      throw new IllegalArgumentException("Catalog event is missing the product snapshot");
    }
    ProductLifecycleStatus.valueOf(lifecycleStatus);
    if (CatalogProductEventType.PRODUCT_TRANSLATION_UPDATED.name().equals(eventType)
      && (locale == null || locale.isBlank())) {
      throw new IllegalArgumentException("Catalog translation event is missing locale");
    }
  }

  private static String normalized(String value) {
    return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
  }
}

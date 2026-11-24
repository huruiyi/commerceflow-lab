package vip.fairy.commerceflow.catalog.application;

import vip.fairy.commerceflow.catalog.domain.Product;
import vip.fairy.commerceflow.catalog.domain.ProductLifecycleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** catalog 商品变更事件第一版契约。 */
public record CatalogProductEventV1(int schemaVersion, String eventId, String eventType,
                                    Instant occurredAt, Long productId, Long merchantId,
                                    long businessVersion, String name, String brief,
                                    String description, BigDecimal price, String imageUrl,
                                    String thumbnailUrl, String lifecycleStatus, String locale,
                                    String correlationId, String causationId) {

  public static final int VERSION = 1;

  public static CatalogProductEventV1 from(Product product, CatalogProductEventType type,
                                           String locale, String correlationId,
                                           String causationId) {
    return from(product, type, locale, correlationId, causationId, product.getLifecycleStatus());
  }

  static CatalogProductEventV1 from(Product product, CatalogProductEventType type, String locale, String correlationId,
                                    String causationId, String lifecycleStatus) {
    return new CatalogProductEventV1(VERSION, UUID.randomUUID().toString(), type.name(),
      Instant.now(), product.getId(), product.getMerchantId(), product.getBusinessVersion(),
      product.getName(), product.getBrief(), product.getDescription(), product.getPrice(),
      product.getImageUrl(), product.getThumbnailUrl(), lifecycleStatus, locale,
      normalized(correlationId), normalized(causationId));
  }

  public static CatalogProductEventV1 translation(Product product, String locale, String name,
                                                   String brief, String description,
                                                   String correlationId, String causationId) {
    CatalogProductEventV1 base = from(product, CatalogProductEventType.PRODUCT_TRANSLATION_UPDATED,
      locale, correlationId, causationId);
    return new CatalogProductEventV1(base.schemaVersion, base.eventId, base.eventType,
      base.occurredAt, base.productId, base.merchantId, base.businessVersion, name, brief,
      description, base.price, base.imageUrl, base.thumbnailUrl, base.lifecycleStatus, locale,
      base.correlationId, base.causationId);
  }

  public void validate() {
    UUID.fromString(eventId);
    CatalogProductEventType.valueOf(eventType);
    ProductLifecycleStatus.valueOf(lifecycleStatus);
    if (schemaVersion != VERSION || productId == null || merchantId == null || businessVersion <= 0
        || correlationId.isBlank() || causationId.isBlank() || name.isBlank()
        || price == null || price.signum() <= 0) {
      throw new IllegalArgumentException("Catalog event is missing required fields");
    }
  }

  private static String normalized(String value) {
    return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
  }
}

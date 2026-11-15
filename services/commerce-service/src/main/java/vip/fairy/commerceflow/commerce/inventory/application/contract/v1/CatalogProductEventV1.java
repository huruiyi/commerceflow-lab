package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inventory 消费的 catalog 商品事件第一版契约。 */
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

  public CatalogProductEventTypeV1 validatedType() {
    try {
      UUID.fromString(eventId);
      CatalogProductEventTypeV1 type = CatalogProductEventTypeV1.valueOf(eventType);
      if (schemaVersion != VERSION || occurredAt == null || productId == null || productId <= 0
          || merchantId == null || merchantId <= 0 || businessVersion <= 0 || blank(name)
          || price == null || price.signum() <= 0 || blank(thumbnailUrl)
          || !("ACTIVE".equals(lifecycleStatus) || "INACTIVE".equals(lifecycleStatus))
          || blank(correlationId) || blank(causationId)) {
        throw new IllegalArgumentException("Catalog product event is missing required fields");
      }
      return type;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Catalog product event is invalid", exception);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

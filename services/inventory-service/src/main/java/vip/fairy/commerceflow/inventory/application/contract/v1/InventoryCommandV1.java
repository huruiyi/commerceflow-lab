package vip.fairy.commerceflow.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

public record InventoryCommandV1(
  int schemaVersion,
  String commandId,
  InventoryCommandTypeV1 commandType,
  Instant occurredAt,
  String producer,
  Long orderId,
  List<Item> items,
  String reason,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;
  public static final Pattern TRACEPARENT = Pattern.compile(
    "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

  public InventoryCommandV1 {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(Long productId, String productName, int quantity) {
  }
}

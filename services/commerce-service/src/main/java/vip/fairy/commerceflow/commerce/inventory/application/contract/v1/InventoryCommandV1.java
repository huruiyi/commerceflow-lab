package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

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

  public static InventoryCommandV1 reserve(String commandId, Long orderId, List<Item> items) {
    return command(commandId, InventoryCommandTypeV1.RESERVE, "ordering", orderId, items,
      "Checkout inventory reservation", commandId);
  }

  public static InventoryCommandV1 commit(String commandId, Long orderId, String causationId) {
    return command(commandId, InventoryCommandTypeV1.COMMIT, "payment", orderId, List.of(),
      "Payment completed the inventory reservation", causationId);
  }

  public static InventoryCommandV1 release(String commandId, Long orderId, String reason,
                                           String causationId) {
    return command(commandId, InventoryCommandTypeV1.RELEASE, "ordering", orderId, List.of(),
      reason, causationId);
  }

  public static InventoryCommandV1 refund(String commandId, Long orderId, String causationId) {
    return command(commandId, InventoryCommandTypeV1.REFUND, "refund", orderId, List.of(),
      "Successful refund returned sold stock", causationId);
  }

  private static InventoryCommandV1 command(
    String commandId,
    InventoryCommandTypeV1 commandType,
    String producer,
    Long orderId,
    List<Item> items,
    String reason,
    String causationId
  ) {
    return new InventoryCommandV1(
      VERSION,
      commandId,
      commandType,
      Instant.now(),
      producer,
      orderId,
      items,
      reason,
      commandId,
      causationId,
      randomTraceparent()
    );
  }

  private static String randomTraceparent() {
    SpanContext current = Span.current().getSpanContext();
    if (current.isValid()) {
      return "00-" + current.getTraceId() + "-" + current.getSpanId() + "-"
        + current.getTraceFlags().asHex();
    }
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }

  public record Item(Long productId, String productName, int quantity) {
  }
}

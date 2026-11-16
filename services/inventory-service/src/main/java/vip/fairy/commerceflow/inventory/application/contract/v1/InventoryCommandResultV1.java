package vip.fairy.commerceflow.inventory.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record InventoryCommandResultV1(
  int schemaVersion,
  String resultId,
  String commandId,
  InventoryCommandTypeV1 commandType,
  String outcome,
  Instant occurredAt,
  String errorCode,
  String message,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static InventoryCommandResultV1 succeeded(InventoryCommandV1 command) {
    return new InventoryCommandResultV1(1, UUID.randomUUID().toString(), command.commandId(),
      command.commandType(), "SUCCEEDED", Instant.now(), null, "Inventory command applied",
      command.correlationId(), command.commandId(), command.traceparent());
  }
}

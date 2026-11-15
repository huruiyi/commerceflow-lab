package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;
import java.util.UUID;

public record InventoryCommandResultV1(
  int schemaVersion,
  String resultId,
  String commandId,
  InventoryCommandTypeV1 commandType,
  InventoryCommandResultOutcomeV1 outcome,
  Instant occurredAt,
  String errorCode,
  String message,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;

  public static InventoryCommandResultV1 succeeded(InventoryCommandV1 command) {
    return new InventoryCommandResultV1(VERSION, UUID.randomUUID().toString(),
      command.commandId(), command.commandType(), InventoryCommandResultOutcomeV1.SUCCEEDED,
      Instant.now(), null, "Inventory command applied", command.correlationId(),
      command.commandId(), command.traceparent());
  }

  public void validate() {
    try {
      UUID.fromString(resultId);
      if (schemaVersion != VERSION || blank(commandId) || commandType == null || outcome == null
          || occurredAt == null || blank(message) || blank(correlationId) || blank(causationId)
          || blank(traceparent) || !InventoryCommandV1.TRACEPARENT.matcher(traceparent).matches()
          || (outcome == InventoryCommandResultOutcomeV1.REJECTED && blank(errorCode))) {
        throw new IllegalArgumentException("Inventory command result is missing required fields");
      }
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Inventory command result is invalid", exception);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

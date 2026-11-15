package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import org.springframework.stereotype.Component;

@Component
public class InventoryCommandValidatorV1 {

  public void validate(InventoryCommandV1 command) {
    if (command == null) {
      throw new IllegalArgumentException("Inventory command is required");
    }
    if (command.schemaVersion() != InventoryCommandV1.VERSION) {
      throw new IllegalArgumentException("Unsupported inventory command schemaVersion");
    }
    require(command.commandId(), "commandId");
    require(command.producer(), "producer");
    require(command.correlationId(), "correlationId");
    require(command.causationId(), "causationId");
    require(command.traceparent(), "traceparent");
    if (command.commandType() == null) {
      throw new IllegalArgumentException("Inventory command requires commandType");
    }
    if (command.occurredAt() == null) {
      throw new IllegalArgumentException("Inventory command requires occurredAt");
    }
    if (command.orderId() == null || command.orderId() <= 0) {
      throw new IllegalArgumentException("Inventory command requires a positive orderId");
    }
    if (!InventoryCommandV1.TRACEPARENT.matcher(command.traceparent()).matches()) {
      throw new IllegalArgumentException("Inventory command traceparent is invalid");
    }
    validatePayload(command);
  }

  private void validatePayload(InventoryCommandV1 command) {
    if (command.commandType() == InventoryCommandTypeV1.RESERVE) {
      if (command.items().isEmpty()) {
        throw new IllegalArgumentException("RESERVE requires at least one item");
      }
      for (InventoryCommandV1.Item item : command.items()) {
        if (item.productId() == null || item.productId() <= 0) {
          throw new IllegalArgumentException("RESERVE item requires a positive productId");
        }
        require(item.productName(), "item.productName");
        if (item.quantity() <= 0) {
          throw new IllegalArgumentException("RESERVE item quantity must be positive");
        }
      }
      return;
    }
    if (!command.items().isEmpty()) {
      throw new IllegalArgumentException(command.commandType() + " must not contain items");
    }
    require(command.reason(), "reason");
  }

  private void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Inventory command requires " + field);
    }
  }
}

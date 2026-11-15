package vip.fairy.commerceflow.commerce.inventory.application;

import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandSemantics;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandValidatorV1;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandInboxMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryCommandHandlerV1 {

  private final InventoryCommandValidatorV1 validator;
  private final InventoryCommandInboxMapper inbox;
  private final InventoryService inventory;

  public InventoryCommandHandlerV1(InventoryCommandValidatorV1 validator,
                                   InventoryCommandInboxMapper inbox,
                                   InventoryService inventory) {
    this.validator = validator;
    this.inbox = inbox;
    this.inventory = inventory;
  }

  @Transactional
  public void handle(InventoryCommandV1 command) {
    validator.validate(command);
    String fingerprint = InventoryCommandSemantics.fingerprint(command);
    if (inbox.claim(command.commandId(), command.commandType().name(), command.orderId(), fingerprint) != 1) {
      String existing = inbox.payloadFingerprint(command.commandId());
      if (!fingerprint.equals(existing)) {
        throw new IllegalArgumentException("Inventory commandId was reused with a different payload");
      }
      return;
    }
    apply(command);
    if (inbox.complete(command.commandId()) != 1) {
      throw new IllegalStateException("Inventory command inbox changed concurrently");
    }
  }

  private void apply(InventoryCommandV1 command) {
    switch (command.commandType()) {
      case RESERVE -> inventory.reserve(command.orderId(), command.items());
      case COMMIT -> inventory.commit(command.orderId(), command.commandId());
      case RELEASE -> inventory.releaseCancelledOrder(
        command.orderId(), command.commandId(), command.reason());
      case REFUND -> inventory.refund(command.orderId(), command.commandId());
    }
  }

}

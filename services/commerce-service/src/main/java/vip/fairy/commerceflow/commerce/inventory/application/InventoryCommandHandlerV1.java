package vip.fairy.commerceflow.commerce.inventory.application;

import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandValidatorV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandInboxMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryCommandHandlerV1 implements InventoryCommandPort {

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

  @Override
  @Transactional
  public void handle(InventoryCommandV1 command) {
    validator.validate(command);
    String fingerprint = fingerprint(command);
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

  private String fingerprint(InventoryCommandV1 command) {
    String items = command.items().stream()
      .map(item -> item.productId() + ":" + field(item.productName()) + ":" + item.quantity())
      .collect(Collectors.joining("\n"));
    String semanticPayload = command.commandType() + "\n" + field(command.producer()) + "\n"
      + command.orderId() + "\n" + field(command.reason()) + "\n" + items;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(semanticPayload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String field(String value) {
    return value == null ? "-1:" : value.length() + ":" + value;
  }
}

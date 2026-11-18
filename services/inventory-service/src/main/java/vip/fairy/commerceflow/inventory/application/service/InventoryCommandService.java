package vip.fairy.commerceflow.inventory.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandTypeV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.inventory.application.port.InventoryCommandUseCase;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryCommandInboxMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.DeliveredInventoryReconciliationMapper;

@Service
public class InventoryCommandService implements InventoryCommandUseCase {

  private final InventoryCommandInboxMapper inbox;
  private final InventoryBalanceService inventory;
  private final DeliveredInventoryReconciliationMapper reconciliation;

  public InventoryCommandService(InventoryCommandInboxMapper inbox,
                                 InventoryBalanceService inventory,
                                 DeliveredInventoryReconciliationMapper reconciliation) {
    this.inbox = inbox;
    this.inventory = inventory;
    this.reconciliation = reconciliation;
  }

  @Override
  @Transactional
  public InventoryCommandResultV1 handle(InventoryCommandV1 command) {
    validate(command);
    String fingerprint = fingerprint(command);
    if (inbox.claim(command.commandId(), command.commandType().name(), command.orderId(),
        fingerprint) != 1) {
      if (!fingerprint.equals(inbox.payloadFingerprint(command.commandId()))) {
        throw new IllegalArgumentException("Inventory commandId was reused with a different payload");
      }
      String rejection = inbox.rejectionMessage(command.commandId());
      if (rejection == null && command.commandType() == InventoryCommandTypeV1.COMMIT) {
        inventory.requireCommittedReservations(command.orderId());
      }
      return rejection == null ? InventoryCommandResultV1.succeeded(command)
        : InventoryCommandResultV1.reservationRejected(command, rejection);
    }
    if (command.commandType() == InventoryCommandTypeV1.RESERVE
        && reconciliation.fingerprint(command.orderId()) != null) {
      throw new InventoryBusinessRuleException("Order already has completed warehouse reconciliation");
    }
    try {
      apply(command);
    } catch (InventoryReservationRejectedException exception) {
      if (inbox.reject(command.commandId(), exception.getMessage()) != 1) {
        throw new IllegalStateException("Inventory command inbox changed concurrently");
      }
      return InventoryCommandResultV1.reservationRejected(command, exception.getMessage());
    }
    if (inbox.complete(command.commandId()) != 1) {
      throw new IllegalStateException("Inventory command inbox changed concurrently");
    }
    return InventoryCommandResultV1.succeeded(command);
  }

  private void apply(InventoryCommandV1 command) {
    switch (command.commandType()) {
      case RESERVE -> inventory.reserve(command.orderId(), command.items());
      case COMMIT -> inventory.commit(command.orderId(), command.commandId());
      case RELEASE -> inventory.release(command.orderId(), command.commandId(), command.reason());
      case REFUND -> inventory.refund(command.orderId(), command.commandId());
    }
  }

  private void validate(InventoryCommandV1 command) {
    if (command == null || command.schemaVersion() != InventoryCommandV1.VERSION
        || command.commandType() == null || command.occurredAt() == null
        || command.orderId() == null || command.orderId() <= 0) {
      throw new IllegalArgumentException("Inventory command envelope is invalid");
    }
    require(command.commandId(), "commandId");
    require(command.producer(), "producer");
    require(command.correlationId(), "correlationId");
    require(command.causationId(), "causationId");
    if (command.traceparent() == null
        || !InventoryCommandV1.TRACEPARENT.matcher(command.traceparent()).matches()) {
      throw new IllegalArgumentException("Inventory command traceparent is invalid");
    }
    if (command.commandType() == InventoryCommandTypeV1.RESERVE) {
      if (command.items().isEmpty()) {
        throw new IllegalArgumentException("RESERVE requires at least one item");
      }
      for (InventoryCommandV1.Item item : command.items()) {
        if (item.productId() == null || item.productId() <= 0 || item.quantity() <= 0) {
          throw new IllegalArgumentException("RESERVE item is invalid");
        }
        require(item.productName(), "item.productName");
      }
    } else if (!command.items().isEmpty()) {
      throw new IllegalArgumentException(command.commandType() + " must not contain items");
    } else {
      require(command.reason(), "reason");
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

  private void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Inventory command requires " + field);
    }
  }
}

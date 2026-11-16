package vip.fairy.commerceflow.commerce.inventory.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.DeliveryRecoveryView;

@Service
public class InventoryCommandRecoveryService {

  private final InventoryCommandDeliveryMapper delivery;
  private final InventoryCommandPort commands;
  private final ObjectMapper mapper;

  public InventoryCommandRecoveryService(InventoryCommandDeliveryMapper delivery,
                                         InventoryCommandPort commands,
                                         ObjectMapper mapper) {
    this.delivery = delivery;
    this.commands = commands;
    this.mapper = mapper;
  }

  public List<RecoveryView> reconciliationRequired(int limit) {
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("Inventory recovery limit must be between 1 and 200");
    }
    return delivery.reconciliationRequired(limit).stream().map(RecoveryView::from).toList();
  }

  @Transactional
  public RecoveryView retry(String commandId, String recoveredBy, String reason) {
    if (recoveredBy == null || recoveredBy.isBlank() || reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Inventory recovery actor and reason are required");
    }
    InventoryCommandV1 command = command(commandId);
    if (delivery.reopen(commandId, recoveredBy, reason.trim()) != 1) {
      throw new IllegalStateException("Inventory command is not awaiting reconciliation");
    }
    commands.handle(command);
    return RecoveryView.from(delivery.byCommandId(commandId));
  }

  private InventoryCommandV1 command(String commandId) {
    try {
      String payload = delivery.payload(commandId);
      if (payload == null) {
        throw new IllegalArgumentException("Inventory command delivery was not found");
      }
      return mapper.readValue(payload, InventoryCommandV1.class);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Inventory recovery payload is invalid", exception);
    }
  }

  public record RecoveryView(String commandId, String commandType, Long orderId, String status,
                             int attempts, String lastError, String recoveredBy,
                             String recoveryReason, java.time.Instant recoveredAt,
                             java.time.Instant createdAt) {

    private static RecoveryView from(DeliveryRecoveryView row) {
      return new RecoveryView(row.commandId(), row.commandType(), row.orderId(), row.status(),
        row.attempts(), row.lastError(), row.recoveredBy(), row.recoveryReason(),
        row.recoveredAt(), row.createdAt());
    }
  }
}

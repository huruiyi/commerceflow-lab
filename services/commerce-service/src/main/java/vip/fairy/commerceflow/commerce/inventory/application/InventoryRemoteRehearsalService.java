package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.InventoryRemoteCommandClient;

@Service
public class InventoryRemoteRehearsalService {

  private final boolean enabled;
  private final InventoryCommandDeliveryMapper delivery;
  private final InventoryRemoteCommandClient remote;
  private final ObjectMapper mapper;

  public InventoryRemoteRehearsalService(
    @Value("${commerceflow.inventory.remote.rehearsal-enabled:false}") boolean enabled,
    InventoryCommandDeliveryMapper delivery,
    InventoryRemoteCommandClient remote,
    ObjectMapper mapper
  ) {
    this.enabled = enabled;
    this.delivery = delivery;
    this.remote = remote;
    this.mapper = mapper;
  }

  public InventoryCommandResultV1 replayAcknowledged(String commandId) {
    if (!enabled) {
      throw new IllegalStateException("Inventory remote rehearsal is disabled");
    }
    if (!"ACKNOWLEDGED".equals(delivery.status(commandId))) {
      throw new IllegalStateException("Only an acknowledged command can be rehearsed");
    }
    InventoryCommandV1 command = command(commandId);
    InventoryCommandResultV1 result = remote.send(command);
    delivery.recordResult(result.resultId(), result.commandId(), result.commandType().name(),
      result.outcome().name(), result.occurredAt(), result.errorCode(), result.message(),
      result.correlationId(), result.causationId(), result.traceparent());
    return result;
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
      throw new IllegalStateException("Inventory command payload is invalid", exception);
    }
  }
}

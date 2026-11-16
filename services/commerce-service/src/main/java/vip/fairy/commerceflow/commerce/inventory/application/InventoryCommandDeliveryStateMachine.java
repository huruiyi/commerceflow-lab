package vip.fairy.commerceflow.commerce.inventory.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultOutcomeV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;

@Service
public class InventoryCommandDeliveryStateMachine {

  private final InventoryCommandDeliveryMapper delivery;
  private final ObjectMapper mapper;
  private final List<InventoryCommandPort.StateProjection> projections;

  public InventoryCommandDeliveryStateMachine(InventoryCommandDeliveryMapper delivery,
                                              ObjectMapper mapper,
                                              List<InventoryCommandPort.StateProjection> projections) {
    this.delivery = delivery;
    this.mapper = mapper;
    this.projections = projections;
  }

  @Transactional
  public int markInFlight(String commandId, Duration resultTimeout) {
    if (resultTimeout == null || resultTimeout.isNegative() || resultTimeout.isZero()) {
      throw new IllegalArgumentException("Inventory result timeout must be positive");
    }
    return delivery.markInFlight(commandId, Instant.now().plus(resultTimeout));
  }

  public List<String> dispatchable(int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Inventory delivery batch size must be between 1 and 500");
    }
    return delivery.dispatchable(limit);
  }

  public InventoryCommandV1 command(String commandId) {
    return readCommand(commandId);
  }

  @Transactional
  public void acknowledge(InventoryCommandV1 command, InventoryCommandResultV1 result) {
    result.validate();
    delivery.recordResult(result.resultId(), result.commandId(), result.commandType().name(),
      result.outcome().name(), result.occurredAt(), result.errorCode(), result.message(),
      result.correlationId(), result.causationId(), result.traceparent());
    if (delivery.acknowledge(command.commandId()) != 1) {
      throw new IllegalStateException("Inventory command delivery changed concurrently");
    }
    if (result.outcome() == InventoryCommandResultOutcomeV1.SUCCEEDED) {
      projections.forEach(projection -> projection.succeeded(command));
    } else {
      projections.forEach(projection -> projection.reconciliationRequired(command));
    }
  }

  @Transactional
  public int recoverExpired(int maxAttempts, Duration retryDelay) {
    if (maxAttempts < 1 || retryDelay == null || retryDelay.isNegative()) {
      throw new IllegalArgumentException("Inventory delivery recovery policy is invalid");
    }
    var expired = delivery.expiredForReconciliation(maxAttempts);
    int changed = delivery.recoverExpired(maxAttempts, Instant.now().plus(retryDelay));
    expired.forEach(row -> projections.forEach(projection ->
      projection.reconciliationRequired(readCommand(row.commandId()))));
    return changed;
  }

  private InventoryCommandV1 readCommand(String commandId) {
    try {
      return mapper.readValue(delivery.payload(commandId), InventoryCommandV1.class);
    } catch (Exception exception) {
      throw new IllegalStateException("Inventory recovery payload is invalid", exception);
    }
  }
}

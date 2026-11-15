package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;
import vip.fairy.commerceflow.commerce.inventory.application.port.InventoryCommandPort;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

  @Transactional
  public int recoverExpired(int maxAttempts, Duration retryDelay) {
    if (maxAttempts < 1 || retryDelay == null || retryDelay.isNegative()) {
      throw new IllegalArgumentException("Inventory delivery recovery policy is invalid");
    }
    var expired = delivery.expiredForReconciliation(maxAttempts);
    int changed = delivery.recoverExpired(maxAttempts, Instant.now().plus(retryDelay));
    expired.forEach(row -> projections.forEach(projection ->
      projection.reconciliationRequired(command(row.commandId()))));
    return changed;
  }

  private InventoryCommandV1 command(String commandId) {
    try {
      return mapper.readValue(delivery.payload(commandId), InventoryCommandV1.class);
    } catch (Exception exception) {
      throw new IllegalStateException("Inventory recovery payload is invalid", exception);
    }
  }
}

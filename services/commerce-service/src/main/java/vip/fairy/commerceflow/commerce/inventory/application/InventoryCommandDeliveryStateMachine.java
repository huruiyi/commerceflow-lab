package vip.fairy.commerceflow.commerce.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryCommandDeliveryMapper;

import java.time.Duration;
import java.time.Instant;

@Service
public class InventoryCommandDeliveryStateMachine {

  private final InventoryCommandDeliveryMapper delivery;

  public InventoryCommandDeliveryStateMachine(InventoryCommandDeliveryMapper delivery) {
    this.delivery = delivery;
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
    return delivery.recoverExpired(maxAttempts, Instant.now().plus(retryDelay));
  }
}

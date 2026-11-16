package vip.fairy.commerceflow.inventory.infrastructure.transport;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InventoryResultDelayLab {

  private final Duration delay;

  public InventoryResultDelayLab(
    @Value("${commerceflow.inventory.result-delay-lab:0s}") Duration delay
  ) {
    if (delay == null || delay.isNegative()) {
      throw new IllegalArgumentException("Inventory result delay must not be negative");
    }
    this.delay = delay;
  }

  public void pauseAfterCommit() {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Inventory result delay was interrupted", exception);
    }
  }
}

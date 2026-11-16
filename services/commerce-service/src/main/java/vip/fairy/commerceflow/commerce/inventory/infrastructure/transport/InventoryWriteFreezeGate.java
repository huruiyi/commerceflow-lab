package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InventoryWriteFreezeGate {

  private final boolean frozen;

  public InventoryWriteFreezeGate(
    @Value("${commerceflow.inventory.cutover.write-frozen:false}") boolean frozen
  ) {
    this.frozen = frozen;
  }

  public void requireWritable() {
    if (frozen) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Inventory writes are frozen for final catch-up");
    }
  }
}

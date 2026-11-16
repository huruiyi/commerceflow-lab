package vip.fairy.commerceflow.inventory.infrastructure.transport;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InventoryTrafficGate {

  private final InventoryTrafficProperties properties;

  public InventoryTrafficGate(InventoryTrafficProperties properties) {
    this.properties = properties;
  }

  public void requireOpen() {
    if (!properties.acceptingTraffic()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Inventory cutover gate is closed");
    }
  }
}

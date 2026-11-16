package vip.fairy.commerceflow.commerce.inventory.infrastructure.transport;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class InventoryRemoteUnavailableException extends ResponseStatusException {

  public InventoryRemoteUnavailableException(Throwable cause) {
    super(HttpStatus.SERVICE_UNAVAILABLE, "Independent inventory service is unavailable", cause);
  }
}

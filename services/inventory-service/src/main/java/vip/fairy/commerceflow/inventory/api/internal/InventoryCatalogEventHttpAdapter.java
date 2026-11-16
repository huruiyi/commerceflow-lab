package vip.fairy.commerceflow.inventory.api.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.inventory.application.service.InventoryCatalogEventService;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
@RequestMapping("/internal/v1/inventory/catalog-events")
public class InventoryCatalogEventHttpAdapter {

  private final InventoryTrafficGate trafficGate;
  private final InventoryCatalogEventService events;

  public InventoryCatalogEventHttpAdapter(InventoryTrafficGate trafficGate,
                                          InventoryCatalogEventService events) {
    this.trafficGate = trafficGate;
    this.events = events;
  }

  @PostMapping
  ResponseEntity<Void> handle(@RequestBody String payload, HttpServletRequest request) {
    trafficGate.requireOpen();
    events.handlePayload(payload, request.getHeader("X-CommerceFlow-Content-SHA256"));
    return ResponseEntity.noContent().build();
  }
}

package vip.fairy.commerceflow.commerce.inventory.api.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCatalogEventHandlerV1;

@RestController
@RequestMapping("/internal/v1/inventory/catalog-events")
public class InventoryCatalogEventController {

  private final InventoryCatalogEventHandlerV1 handler;

  public InventoryCatalogEventController(InventoryCatalogEventHandlerV1 handler) {
    this.handler = handler;
  }

  @PostMapping
  ResponseEntity<Void> consume(@RequestBody String payload, HttpServletRequest request) {
    String contentHash = request.getHeader("X-CommerceFlow-Content-SHA256");
    handler.handlePayload(payload, contentHash);
    return ResponseEntity.noContent().build();
  }
}
